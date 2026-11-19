package dev.herdwatch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageInfo
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.herdwatch.R
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Replacing our own APK needs three platform things the ViewModel must not know about: a
 * network download into a place the installer can read, the "install unknown apps" grant, and
 * handing the file to the system installer.
 *
 * It is an interface so the update flow can be unit tested without an Android context, and so the
 * ViewModel keeps the same shape as [dev.herdwatch.ui.TerminalController]: state out, side effects
 * injected.
 */
interface UpdateInstaller {

    /** Whether Herdly may launch the package installer for a file it downloaded. */
    fun canInstall(): Boolean

    /** Sends the user to the per-app "install unknown apps" screen. */
    fun requestInstallPermission()

    /**
     * Downloads [url] and returns the APK on disk, reporting whole-percent progress.
     *
     * Throws [IOException] with a user-facing reason when the transfer fails, [InvalidApkException]
     * when the bytes are not a Herdly build.
     */
    suspend fun download(url: String, version: String, onProgress: (Int) -> Unit): File

    /** Hands [file] to the system installer. Throws [InvalidApkException] when it is not ours. */
    fun install(file: File)
}

/** The downloaded file is not an installable Herdly build; [message] is user-facing. */
class InvalidApkException(message: String) : Exception(message)

/**
 * Download-then-install without leaving the app.
 *
 * The file goes to `cacheDir/update` and is shared with the system installer through a
 * [FileProvider]: handing out a `file://` URI throws on API 24+, and the installer runs in another
 * process, so it needs an explicit read grant.
 */
class AndroidUpdateInstaller(private val context: Context) : UpdateInstaller {

    override fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some OEM builds ship the screen without the per-app fragment; fall back to the list.
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    override suspend fun download(url: String, version: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "update").apply { mkdirs() }
            // A half-written APK from an interrupted attempt must never be offered again.
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "herdly-$version.apk")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Herdly")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("http $code")
                val total = connection.contentLengthLong
                var written = 0L
                var lastPercent = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && written != total) throw IOException("short read")
            } catch (t: Throwable) {
                target.delete()
                throw t
            } finally {
                connection.disconnect()
            }
            verify(target)
            target
        }

    override fun install(file: File) {
        verify(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Refuses anything that is not a newer Herdly build.
     *
     * A failed CDN response saved as `.apk` would otherwise reach the system installer and show the
     * user a parse error, and an older asset (or someone else's APK) must not be offered at all.
     */
    private fun verify(file: File) {
        if (!file.isFile || file.length() == 0L) {
            throw InvalidApkException(context.getString(R.string.update_bad_apk))
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info == null || info.packageName != context.packageName) {
            throw InvalidApkException(context.getString(R.string.update_bad_apk))
        }
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCodeLong()
        }.getOrDefault(0L)
        if (info.versionCodeLong() <= installed) {
            throw InvalidApkException(context.getString(R.string.update_older_apk))
        }
    }

    /**
     * `PackageInfo.longVersionCode` only exists from API 28 while the app supports API 26.
     * Without this branch the comparison silently reads the low 32 bits on older systems.
     */
    private fun PackageInfo.versionCodeLong(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
