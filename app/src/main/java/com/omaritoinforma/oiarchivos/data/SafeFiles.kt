package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Never follow links during recursive operations; never truncate the destination in place. */
object SafeFiles {
    fun validName(name: String): Boolean = name.isNotBlank() && name !in setOf(".", "..") &&
        name.none { it == '/' || it == '\\' || it == '\u0000' }

    fun requireName(name: String) {
        if (!validName(name)) throw IOException("Nombre de archivo no válido")
    }

    fun walk(root: File): Sequence<File> = root.walkTopDown().maxDepth(128)
        .onEnter { !Files.isSymbolicLink(it.toPath()) }
        .filter { !Files.isSymbolicLink(it.toPath()) }

    fun requireRegular(file: File) {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("No se siguen enlaces simbólicos: ${file.name}")
        if (!file.exists()) throw IOException("Ya no existe «${file.name}»")
    }

    fun commit(temp: File, target: File, replace: Boolean = true) {
        val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else arrayOf(StandardCopyOption.ATOMIC_MOVE)
        // ATOMIC_MOVE is allowed to replace an existing target, even without REPLACE_EXISTING.
        if (!replace && target.exists()) throw IOException("Ya existe «${target.name}»")
        try {
            Files.move(temp.toPath(), target.toPath(), *options)
        } catch (_: AtomicMoveNotSupportedException) {
            if (replace) Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(temp.toPath(), target.toPath())
        }
    }

    fun writeAtomic(target: File, block: (File) -> Unit) {
        val parent = target.absoluteFile.parentFile ?: throw IOException("Destino no válido")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("No se pudo crear el destino")
        val temp = File.createTempFile(".oi-part-", ".tmp", parent)
        try {
            block(temp)
            commit(temp, target)
        } finally { temp.delete() }
    }

    fun archiveTarget(root: File, name: String): File {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." } || normalized.contains('\u0000'))
            throw IOException("Ruta insegura en el archivo comprimido")
        val target = File(root, normalized).canonicalFile
        if (!target.path.startsWith(root.canonicalPath + File.separator)) throw IOException("Ruta fuera del destino")
        return target
    }
}
