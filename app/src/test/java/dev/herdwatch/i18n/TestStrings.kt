package dev.herdwatch.i18n

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Gives unit tests the real Chinese strings without an Android runtime: it parses
 * every XML file under `app/src/main/res/values`, maps resource names to the generated `R` ids by reflection and
 * installs itself as [Str]'s provider.
 *
 * Tests then assert on [text] instead of a hard-coded translation, so a copy change does not break
 * behaviour tests and the same assertion keeps working in English builds.
 */
object TestStrings {
    private val templates: Map<String, String> by lazy { parse("src/main/res/values") }
    private val plurals: Map<String, String> by lazy { parsePlurals("src/main/res/values") }
    private val namesById: Map<Int, String> by lazy { resourceNames("string") }
    private val pluralNamesById: Map<Int, String> by lazy { resourceNames("plurals") }

    fun install() {
        Str.install(object : Str.Provider {
            override fun get(id: Int, args: Array<out Any>): String {
                val name = namesById[id] ?: return "#$id"
                return format(templates[name] ?: "#$name", args)
            }

            override fun quantity(id: Int, count: Int, args: Array<out Any>): String {
                val name = pluralNamesById[id] ?: return "#$id"
                return format(plurals[name] ?: "#$name", arrayOf(count, *args))
            }
        })
    }

    /** Text of a resource by name, formatted with [args]. Use this in assertions. */
    fun text(name: String, vararg args: Any): String = format(templates[name] ?: "#$name", args)

    private fun format(template: String, args: Array<out Any>): String =
        if (args.isEmpty()) template else String.format(Locale.ROOT, template, *args)

    private fun resourceNames(kind: String): Map<Int, String> = runCatching {
        val clazz = Class.forName("dev.herdwatch.R\$$kind")
        clazz.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
    }.getOrDefault(emptyMap())

    private fun parse(dir: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        xmlFiles(dir).forEach { file ->
            val root = file.documentElement() ?: return@forEach
            val nodes = root.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                out[name] = el.textContent
            }
        }
        return out
    }

    private fun parsePlurals(dir: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        xmlFiles(dir).forEach { file ->
            val root = file.documentElement() ?: return@forEach
            val nodes = root.getElementsByTagName("plurals")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                val items = el.getElementsByTagName("item")
                // Chinese resources have a single "other" form; take the first item available.
                if (items.length > 0) out[name] = (items.item(0) as Element).textContent
            }
        }
        return out
    }

    private fun xmlFiles(dir: String) = File(dir)
        .listFiles { f -> f.isFile && f.name.endsWith(".xml") }
        ?.sortedBy { it.name }
        ?: emptyList()

    private fun File.documentElement(): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newDocumentBuilder().parse(this).documentElement
    }.getOrNull()
}
