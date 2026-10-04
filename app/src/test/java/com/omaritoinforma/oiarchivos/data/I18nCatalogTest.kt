package com.omaritoinforma.oiarchivos.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Recorre el código de la app y comprueba el catálogo de traducciones: cada tr("…") y trKey("…")
 * tiene su texto en inglés, el catálogo no guarda textos que ya no se usan y las marcas {0}, {1}…
 * coinciden. Los textos dentro de tr() no pueden llevar plantillas de Kotlin ni concatenaciones,
 * porque entonces la clave cambiaría al ejecutarse.
 */
class I18nCatalogTest {
    private val sources = File("src/main/java")
    private val catalogFile = File("src/main/assets/i18n/en.tsv")

    private data class Use(val text: String, val where: String)

    private class Scanner(private val src: String, private val file: String) {
        private var i = 0
        val uses = ArrayList<Use>()
        val problems = ArrayList<String>()

        fun run(): Scanner {
            code(untilBrace = false)
            return this
        }

        private fun where() = "$file:${src.substring(0, minOf(i, src.length)).count { it == '\n' } + 1}"

        private fun code(untilBrace: Boolean) {
            var depth = 0
            while (i < src.length) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    src.startsWith("/*", i) -> blockComment()
                    src.startsWith("\"\"\"", i) -> rawString()
                    c == '"' -> {
                        i++
                        string(collect = false)
                    }
                    c == '\'' -> charLiteral()
                    untilBrace && c == '{' -> {
                        depth++
                        i++
                    }
                    untilBrace && c == '}' -> {
                        i++
                        if (depth == 0) return
                        depth--
                    }
                    callAt() != null -> call(callAt()!!)
                    else -> i++
                }
            }
        }

        private fun blockComment() {
            var nesting = 0
            while (i < src.length) {
                if (src.startsWith("/*", i)) {
                    nesting++
                    i += 2
                } else if (src.startsWith("*/", i)) {
                    nesting--
                    i += 2
                    if (nesting == 0) return
                } else i++
            }
        }

        private fun rawString() {
            val end = src.indexOf("\"\"\"", i + 3)
            i = if (end < 0) src.length else end + 3
            while (i < src.length && src[i] == '"') i++
        }

        private fun charLiteral() {
            i++
            if (i < src.length && src[i] == '\\') i += if (src.getOrNull(i + 1) == 'u') 6 else 2 else i++
            if (i < src.length && src[i] == '\'') i++
        }

        /** Si en [i] empieza una llamada tr( o trKey(, su nombre. */
        private fun callAt(): String? {
            val name =
                when {
                    src.startsWith("trKey(", i) -> "trKey"
                    src.startsWith("tr(", i) -> "tr"
                    else -> return null
                }
            val before = src.getOrNull(i - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.' || before == '`')) return null
            return name
        }

        private fun call(name: String) {
            val start = where()
            i += name.length + 1
            while (i < src.length && src[i].isWhitespace()) i++
            when {
                src.startsWith("\"\"\"", i) -> problems += "$start: $name() con una cadena \"\"\""
                src.getOrNull(i) == '"' -> {
                    i++
                    val text = string(collect = true, origin = start) ?: return
                    var j = i
                    while (j < src.length && src[j].isWhitespace()) j++
                    when (src.getOrNull(j)) {
                        ')', ',' -> uses += Use(text, start)
                        else -> problems += "$start: $name(\"${text.take(40)}…\") no termina en la cadena (¿concatenación?)"
                    }
                }
            }
        }

        /** Lee una cadena normal desde justo después de la comilla; con [collect], devuelve su texto. */
        private fun string(collect: Boolean, origin: String = ""): String? {
            val out = StringBuilder()
            var ok = true
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\\' -> {
                        val next = src.getOrNull(i + 1)
                        when (next) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'u' -> {
                                out.append(src.substring(i + 2, i + 6).toInt(16).toChar())
                                i += 4
                            }
                            else -> out.append(next)
                        }
                        i += 2
                    }
                    c == '$' && src.getOrNull(i + 1) == '{' -> {
                        if (collect) {
                            problems += "$origin: plantilla \${…} dentro de tr(): usa {0}"
                            ok = false
                        }
                        i += 2
                        code(untilBrace = true)
                    }
                    c == '$' && src.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        if (collect) {
                            problems += "$origin: plantilla \$nombre dentro de tr(): usa {0}"
                            ok = false
                        }
                        i++
                        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    }
                    c == '"' -> {
                        i++
                        return if (collect && ok) out.toString() else null
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return null
        }
    }

    private val scanned by lazy {
        val scanners =
            sources.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .sortedBy { it.path }
                .map { Scanner(it.readText(Charsets.UTF_8), it.relativeTo(sources).path).run() }
                .toList()
        scanners.flatMap { it.uses } to scanners.flatMap { it.problems }
    }

    private val catalog by lazy { I18n.parse(catalogFile.readText(Charsets.UTF_8)) }

    private fun marks(text: String) = Regex("\\{(\\d+)\\}").findAll(text).map { it.groupValues[1] }.toSortedSet()

    @Test
    fun theScannerReadsLiteralsAndSkipsCommentsAndTemplates() {
        val sample =
            """
            // tr("en un comentario")
            /* tr("en un bloque /* anidado */ todavía") */
            val a = tr("Copiar")
            val b = tr("{0} de {1}", x, y)
            val c = trKey("Pegar")
            val d = "texto con ${'$'}{tr("Dentro de una plantilla")} y ${'$'}valor"
            val e = attr("no es tr")
            val f = tr(variable)
            val g = tr("Comillas \"dobles\" y salto\nde línea")
            val h = tr("mal ${'$'}{x}")
            val i = tr("mal " + otra)
            """.trimIndent()
        val s = Scanner(sample, "muestra.kt").run()
        assertEquals(
            listOf("Copiar", "{0} de {1}", "Pegar", "Dentro de una plantilla", "Comillas \"dobles\" y salto\nde línea"),
            s.uses.map { it.text })
        assertEquals(2, s.problems.size)
    }

    @Test
    fun theRealCatalogTranslatesLabelsAndTextsWithData() {
        try {
            I18n.use(catalog)
            assertEquals("Copy", tr("Copiar"))
            assertEquals("Trash", DrawerEntry.TRASH.label)
            assertEquals("Categories", HomeSection.CATEGORIES.label)
            assertEquals("3 of 7 files", tr("{0} de {1} archivos", 3, 7))
            assertEquals("Portrait", ScreenOrientation.PORTRAIT.label)
            assertEquals("System language", AppLanguage.SYSTEM.label)
            assertEquals("Español", AppLanguage.SPANISH.label) // cada idioma, en su propia lengua
        } finally {
            I18n.use(emptyMap())
        }
        assertEquals("Vertical", ScreenOrientation.PORTRAIT.label)
    }

    @Test
    fun textsInsideTrAreLiteral() {
        val problems = scanned.second
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyTranslatableTextHasItsEnglishTranslation() {
        val missing = scanned.first.filter { it.text !in catalog }.distinctBy { it.text }
        assertTrue(
            "Faltan ${missing.size} traducciones en ${catalogFile.path}:\n" +
                missing.take(60).joinToString("\n") { "${it.where}\t${it.text.replace("\n", "\\n")}" },
            missing.isEmpty())
    }

    @Test
    fun theCatalogHasNoUnusedTexts() {
        val used = scanned.first.map { it.text }.toSet()
        val unused = catalog.keys.filter { it !in used }
        assertTrue("Textos del catálogo que ya no se usan:\n" + unused.take(60).joinToString("\n"), unused.isEmpty())
    }

    @Test
    fun translationsKeepTheirPlaceholdersAndAreNotEmpty() {
        val wrong = catalog.filter { (es, en) -> en.isBlank() || marks(es) != marks(en) }
        assertTrue("Traducciones vacías o con otras marcas {n}:\n" + wrong.entries.take(40).joinToString("\n"), wrong.isEmpty())
        // Las marcas de los textos con datos van seguidas desde {0}: {0}, {1}…
        val gaps =
            scanned.first.map { it.text }.filter { text ->
                marks(text).map { it.toInt() }.sorted() != marks(text).indices.toList()
            }
        assertTrue("Marcas {n} que no empiezan en {0} o tienen huecos:\n" + gaps.joinToString("\n"), gaps.isEmpty())
    }
}
