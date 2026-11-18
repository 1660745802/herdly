package dev.herdwatch.i18n

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Strings for code that has no [android.content.Context] (repositories, SSH clients, parsers).
 *
 * UI code must use `stringResource(...)` / `pluralStringResource(...)` directly — this helper only
 * exists for message construction that happens outside composition.
 *
 * The provider is installed once in [dev.herdwatch.HerdwatchApp]. Unit tests install their own
 * provider (see `TestStrings`) so message text can be asserted without an Android runtime.
 */
object Str {
    interface Provider {
        fun get(@StringRes id: Int, args: Array<out Any>): String
        fun quantity(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
    }

    @Volatile
    private var provider: Provider? = null

    fun install(provider: Provider) {
        this.provider = provider
    }

    fun install(context: Context) {
        val res = context.applicationContext.resources
        install(object : Provider {
            override fun get(id: Int, args: Array<out Any>): String =
                if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

            override fun quantity(id: Int, count: Int, args: Array<out Any>): String =
                res.getQuantityString(id, count, count, *args)
        })
    }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val current = provider ?: return "#$id"
        return current.get(id, args)
    }

    /**
     * Plural form for a count. Chinese has a single form, English has one/other, so counts must
     * never be built by concatenating a number with a unit.
     */
    fun quantity(@PluralsRes id: Int, count: Int, vararg args: Any): String {
        val current = provider ?: return "#$id"
        return current.quantity(id, count, args)
    }
}
