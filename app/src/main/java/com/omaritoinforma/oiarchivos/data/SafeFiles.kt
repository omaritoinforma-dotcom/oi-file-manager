package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Never follow links during recursive operations; never truncate the destination in place. */
object SafeFiles {
    fun validName(name: String): Boolean =
        name.isNotBlank() &&
            name !in setOf(".", "..") &&
            name.none { it == '/' || it == '\\' || it == '\u0000' }

    fun requireName(name: String) {
        if (!validName(name)) throw IOException("Nombre de archivo no válido")
    }

    /**
     * Recorre [root] sin seguir enlaces simbólicos. Con [skipHidden] no devuelve ni entra en lo que
     * empieza por punto; con [recursive] en false solo devuelve [root] y lo que hay justo dentro.
     */
    fun walk(root: File, skipHidden: Boolean = false, recursive: Boolean = true): Sequence<File> {
        fun visible(f: File) = !skipHidden || f == root || !f.name.startsWith(".")
        return root
            .walkTopDown()
            .maxDepth(if (recursive) 128 else 1)
            .onEnter { !Files.isSymbolicLink(it.toPath()) && visible(it) }
            .filter { !Files.isSymbolicLink(it.toPath()) && visible(it) }
    }

    fun requireRegular(file: File) {
        if (Files.isSymbolicLink(file.toPath()))
            throw IOException("No se siguen enlaces simbólicos: ${file.name}")
        if (!file.exists()) throw IOException("Ya no existe «${file.name}»")
    }

    fun commit(temp: File, target: File, replace: Boolean = true) {
        // ATOMIC_MOVE may replace a target even without REPLACE_EXISTING. Use the
        // no-replace operation when creating a new file so concurrent writes cannot be lost.
        if (!replace) {
            Files.move(temp.toPath(), target.toPath())
            return
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun writeAtomic(target: File, block: (File) -> Unit) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("Destino no válido")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear el destino")
        val temp = File.createTempFile(".oi-part-", ".tmp", parent)
        try {
            block(temp)
            commit(temp, target)
        } finally {
            temp.delete()
        }
    }

    fun archiveTarget(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." } ||
            normalized.contains('\u0000'))
            throw IOException("Ruta insegura en el archivo comprimido")
        val target = File(root, normalized).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator))
            throw IOException("Ruta fuera del destino")
        return target
    }
}
