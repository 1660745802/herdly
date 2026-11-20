package dev.herdwatch.data

import android.content.SharedPreferences
import dev.herdwatch.model.HostConfig
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class HostStoreTest {
    private val host = HostConfig("h", "test", "localhost", user = "test", secret = "secret")

    @Test fun missingOrCorruptPrimaryRecoversCurrentBackup() {
        val prefs = MemoryPreferences()
        val store = store(prefs)
        store.save(listOf(host.copy(fingerprint = "SHA256:pinned")))
        prefs.values.remove("hosts.v1")
        assertEquals("SHA256:pinned", store.load().single().fingerprint)
        prefs.values["hosts.v1"] = "corrupt"
        assertEquals("SHA256:pinned", store.load().single().fingerprint)
        store.save(store.load())
        prefs.values.remove("hosts.v1")
        assertEquals("SHA256:pinned", store.load().single().fingerprint)
    }

    @Test fun recoveryNeverRollsBackNewPinOrCredentials() {
        val prefs = MemoryPreferences()
        val store = store(prefs)
        store.save(listOf(host))
        store.save(listOf(host.copy(secret = "replacement", fingerprint = "SHA256:new")))
        prefs.values.remove("hosts.v1")
        assertEquals("replacement", store.load().single().secret)
        assertEquals("SHA256:new", store.load().single().fingerprint)
    }

    @Test fun deletionPurgesCredentialsFromBackupAndRemovesCacheInSameCommit() {
        val prefs = MemoryPreferences()
        val store = store(prefs)
        store.save(listOf(host))
        prefs.values["agents.v1.h"] = "old cache"
        store.save(emptyList(), removedHostIds = setOf("h"))
        assertFalse(prefs.values.containsKey("agents.v1.h"))
        prefs.values.remove("hosts.v1")
        assertTrue(store.load().isEmpty())
        assertFalse(prefs.values.getValue("hosts.v1.bak").contains("secret"))
    }

    @Test fun failedCommitIsObservable() {
        val prefs = MemoryPreferences()
        prefs.commitSucceeds = false
        val result = runCatching { store(prefs).save(listOf(host)) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun unreadableGenerationsCannotBeSilentlyOverwritten() {
        val prefs = MemoryPreferences()
        prefs.values["hosts.v1"] = "broken primary"
        prefs.values["hosts.v1.bak"] = "broken backup"
        val result = runCatching { store(prefs).save(emptyList()) }
        assertTrue(result.exceptionOrNull() is HostStoreUnavailableException)
        assertEquals("broken backup", prefs.values["hosts.v1.bak"])
    }

    @Test fun unreadablePayloadIsReportedNotTreatedAsNoHosts() {
        val prefs = MemoryPreferences()
        prefs.values["hosts.v1"] = "broken primary"
        prefs.values["hosts.v1.bak"] = "broken backup"

        val result = runCatching { store(prefs).load() }

        assertTrue(result.exceptionOrNull() is HostStoreUnavailableException)
        // A failed read must leave the payload alone: it may still be recoverable.
        assertEquals("broken primary", prefs.values["hosts.v1"])
        assertEquals("broken backup", prefs.values["hosts.v1.bak"])
    }

    @Test fun unavailableKeystoreIsReportedWhenSaving() {
        val prefs = MemoryPreferences()
        val store = HostStore(
            prefs.proxy,
            encrypt = { throw IllegalStateException("keystore entry lost") },
            decrypt = { error("must not be called") },
        )

        val result = runCatching { store.save(listOf(host)) }

        assertTrue(result.exceptionOrNull() is HostStoreUnavailableException)
        assertFalse(prefs.values.containsKey("hosts.v1"))
    }

    private fun store(prefs: MemoryPreferences) = HostStore(
        prefs.proxy,
        encrypt = { "encrypted:$it" },
        decrypt = { check(it.startsWith("encrypted:")); it.removePrefix("encrypted:") },
    )

    /** A preferences transaction fake; tests do not need Android Keystore or a device. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, String>()
        var commitSucceeds = true
        val proxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
        ) { _, method, args -> when (method.name) {
            "getString" -> values[args!![0]] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected preferences call: ${method.name}")
        } } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args -> when (method.name) {
                "putString" -> { changes[args!![0] as String] = args[1] as String?; editor }
                "remove" -> { changes[args!![0] as String] = null; editor }
                "commit" -> {
                    if (commitSucceeds) changes.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    commitSucceeds
                }
                else -> error("Unexpected editor call: ${method.name}")
            } } as SharedPreferences.Editor
            return editor
        }
    }
}
