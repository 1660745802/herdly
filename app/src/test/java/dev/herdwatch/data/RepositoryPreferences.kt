package dev.herdwatch.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/** Durable transaction fake shared by repository lifecycle fixtures. */
internal class RepositoryPreferences {
    private val values = mutableMapOf<String, String>()
    var commitSucceeds = true
    private val proxy = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args -> when (method.name) {
        "getString" -> values[args!![0]] ?: args[1]
        "edit" -> editor()
        else -> error("Unexpected preferences call: ${method.name}")
    } } as SharedPreferences

    val store = HostStore(proxy, encrypt = { it }, decrypt = { it })

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
