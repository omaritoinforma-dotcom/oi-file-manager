package com.omaritoinforma.oiarchivos.data

import android.content.Context
import java.util.Locale

/** Idioma de la app («Idioma» de ES). Cada idioma se nombra en su propia lengua. */
enum class AppLanguage(private val name0: String) {
    SPANISH("Español"),
    ENGLISH("English"),
    SYSTEM(trKey("Idioma del sistema"));

    val label: String
        get() = if (this == SYSTEM) tr(name0) else name0
}

/**
 * Traducción de la interfaz al estilo de gettext: en el código el texto se escribe en español y
 * [tr] lo cambia por el del catálogo del idioma elegido (`assets/i18n/en.tsv`). Si un texto no está
 * en el catálogo se muestra en español, nunca vacío. Los textos con datos llevan marcas {0}, {1}…
 *
 * El catálogo es un TSV «español<TAB>inglés» por línea; \n, \t y \\ se escriben escapados. Una
 * prueba JVM comprueba que cada tr("…") del código tiene su traducción.
 */
object I18n {
    @Volatile private var catalog: Map<String, String> = emptyMap()

    /** Idioma que se usa de verdad: con «Idioma del sistema», español si el teléfono está en español. */
    fun effective(language: AppLanguage, system: Locale = Locale.getDefault()): AppLanguage =
        when (language) {
            AppLanguage.SYSTEM -> if (system.language == "es") AppLanguage.SPANISH else AppLanguage.ENGLISH
            else -> language
        }

    /** Carga el catálogo del idioma elegido. Se llama al arrancar el proceso y al cambiar el ajuste. */
    fun apply(ctx: Context, language: AppLanguage) {
        catalog =
            when (effective(language)) {
                AppLanguage.ENGLISH ->
                    runCatching {
                        ctx.assets.open("i18n/en.tsv").bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
                    }.getOrDefault(emptyMap())
                else -> emptyMap()
            }
    }

    /** Solo para las pruebas: usa [entries] como catálogo. */
    internal fun use(entries: Map<String, String>) {
        catalog = entries
    }

    fun tr(text: String): String = catalog[text] ?: text

    fun tr(text: String, vararg args: Any?): String = format(catalog[text] ?: text, args)

    /** Sustituye {0}, {1}… por los argumentos; lo que no sea una marca se deja tal cual. */
    internal fun format(pattern: String, args: Array<out Any?>): String {
        val out = StringBuilder(pattern.length + 16)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '{') {
                val close = pattern.indexOf('}', i + 1)
                val index = if (close > i + 1) pattern.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    out.append(args[index].toString())
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Lee el TSV del catálogo; las líneas vacías y las que empiezan por # se ignoran. */
    internal fun parse(tsv: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for ((number, line) in tsv.split('\n').withIndex()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            require(tab > 0 && line.indexOf('\t', tab + 1) < 0) {
                "Línea ${number + 1} del catálogo: se esperaba «español<TAB>traducción»"
            }
            val key = unescape(line.substring(0, tab))
            require(key !in result) { "Línea ${number + 1} del catálogo: «$key» está repetido" }
            result[key] = unescape(line.substring(tab + 1).trimEnd('\r'))
        }
        return result
    }

    internal fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(text[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/** El texto en el idioma de la app (ver [I18n]). */
fun tr(text: String): String = I18n.tr(text)

/** El texto en el idioma de la app, con {0}, {1}… sustituidos por [args]. */
fun tr(text: String, vararg args: Any?): String = I18n.tr(text, *args)

/**
 * Marca un texto para traducirlo más tarde con [tr], sin traducirlo ahora (por ejemplo, el nombre
 * de un elemento de una lista fija, que se traduce al mostrarlo). La prueba del catálogo lo cuenta.
 */
fun trKey(text: String): String = text
