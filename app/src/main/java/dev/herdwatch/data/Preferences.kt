package dev.herdwatch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class MotionMode { FULL, REDUCED }

enum class DensityMode { COMFORTABLE, COMPACT }

enum class InputMode { LINE, DIRECT }

/** App language. SYSTEM follows the device; the rest override it per app. */
enum class LanguageMode { SYSTEM, ZH, EN }

/**
 * User preference snapshot. Defaults are the product's defaults, so a fresh install reads as
 * designed rather than as "everything off".
 */
data class AppPreferences(
    val language: LanguageMode = LanguageMode.SYSTEM,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val motion: MotionMode = MotionMode.FULL,
    val density: DensityMode = DensityMode.COMFORTABLE,
    val terminalFontSize: Float = 13f,
    val snapshotLines: Int = 400,
    val keyBarRows: Int = 2,
    val longPressRepeat: Boolean = true,
    val inputMode: InputMode = InputMode.LINE,
) {
    companion object {
        const val MIN_FONT = 9f
        const val MAX_FONT = 22f
        val SNAPSHOT_CHOICES = listOf(400, 1200)
    }
}

/**
 * The single source of user preferences. Host credentials stay in [HostStore]; this store only
 * holds choices that are safe to lose, and it commits synchronously so a value the user just
 * changed is never silently dropped.
 */
class PreferencesStore private constructor(
    private val backend: Backend,
) {
    constructor(context: Context) : this(AndroidBackend(context.getSharedPreferences(FILE, Context.MODE_PRIVATE)))

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferences> = _state.asStateFlow()

    fun setLanguage(mode: LanguageMode) = write(KEY_LANGUAGE, mode.name) { it.copy(language = mode) }

    fun setTheme(mode: ThemeMode) = write(KEY_THEME, mode.name) { it.copy(theme = mode) }

    fun setMotion(mode: MotionMode) = write(KEY_MOTION, mode.name) { it.copy(motion = mode) }

    fun setDensity(mode: DensityMode) = write(KEY_DENSITY, mode.name) { it.copy(density = mode) }

    fun setTerminalFontSize(size: Float) = write(
        KEY_FONT,
        size.coerceIn(AppPreferences.MIN_FONT, AppPreferences.MAX_FONT),
    ) { it.copy(terminalFontSize = size.coerceIn(AppPreferences.MIN_FONT, AppPreferences.MAX_FONT)) }

    fun setSnapshotLines(lines: Int) = write(KEY_SNAPSHOT, lines) { it.copy(snapshotLines = lines) }

    fun setKeyBarRows(rows: Int) = write(KEY_KEY_ROWS, rows.coerceIn(1, 2)) { it.copy(keyBarRows = rows.coerceIn(1, 2)) }

    fun setLongPressRepeat(enabled: Boolean) = write(KEY_REPEAT, enabled) { it.copy(longPressRepeat = enabled) }

    fun setInputMode(mode: InputMode) = write(KEY_INPUT_MODE, mode.name) { it.copy(inputMode = mode) }


    private fun <T> write(key: String, value: T, update: (AppPreferences) -> AppPreferences) {
        // commit(): a preference the user just changed must not be lost if the process dies.
        backend.put(key, value.toString())
        _state.update(update)
    }

    private fun read(): AppPreferences {
        val defaults = AppPreferences()
        return AppPreferences(
            language = backend.get(KEY_LANGUAGE)?.let { name -> enumOrNull<LanguageMode>(name) } ?: defaults.language,
            theme = backend.get(KEY_THEME)?.let { name -> enumOrNull<ThemeMode>(name) } ?: defaults.theme,
            motion = backend.get(KEY_MOTION)?.let { name -> enumOrNull<MotionMode>(name) } ?: defaults.motion,
            density = backend.get(KEY_DENSITY)?.let { name -> enumOrNull<DensityMode>(name) } ?: defaults.density,
            terminalFontSize = backend.get(KEY_FONT)?.toFloatOrNull()
                ?.coerceIn(AppPreferences.MIN_FONT, AppPreferences.MAX_FONT) ?: defaults.terminalFontSize,
            snapshotLines = backend.get(KEY_SNAPSHOT)?.toIntOrNull() ?: defaults.snapshotLines,
            keyBarRows = backend.get(KEY_KEY_ROWS)?.toIntOrNull() ?: defaults.keyBarRows,
            longPressRepeat = backend.get(KEY_REPEAT)?.toBooleanStrictOrNull() ?: defaults.longPressRepeat,
            inputMode = backend.get(KEY_INPUT_MODE)?.let { name -> enumOrNull<InputMode>(name) } ?: defaults.inputMode,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    /** Storage seam: SharedPreferences in the app, a plain map in unit tests. */
    internal interface Backend {
        fun get(key: String): String?
        fun put(key: String, value: String)
    }

    private class AndroidBackend(private val prefs: android.content.SharedPreferences) : Backend {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String) {
            prefs.edit().putString(key, value).commit()
        }
    }

    companion object {
        /** In-memory store for unit tests: no Android framework, same semantics. */
        fun inMemory(initial: Map<String, String> = emptyMap()): PreferencesStore {
            val map = initial.toMutableMap()
            return PreferencesStore(object : Backend {
                override fun get(key: String): String? = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }
            })
        }

        const val FILE = "herdwatch.prefs"
        const val KEY_LANGUAGE = "language"
        const val KEY_THEME = "theme"
        const val KEY_MOTION = "motion"
        const val KEY_DENSITY = "density"
        const val KEY_FONT = "terminal.fontSize"
        const val KEY_SNAPSHOT = "pane.snapshotLines"
        const val KEY_KEY_ROWS = "terminal.keyBarRows"
        const val KEY_REPEAT = "terminal.longPressRepeat"
        const val KEY_INPUT_MODE = "terminal.inputMode"
    }
}
