package com.omaritoinforma.oiarchivos.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest

data class PropInfo(val bytes: Long, val files: Int, val folders: Int)

object FileRepo {
    /** Lista una carpeta. Devuelve null si no se puede leer. */
    fun list(dir: File, showHidden: Boolean): List<FileItem>? {
        val children = dir.listFiles() ?: return null
        return children.asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .map { it.toItem() }
            .toList()
    }

    /** Búsqueda recursiva por nombre; emite resultados a medida que los encuentra. */
    fun search(root: File, query: String, showHidden: Boolean, limit: Int = 5000): Flow<FileItem> = flow {
        val q = query.lowercase()
        var count = 0
        val walker = root.walkTopDown()
            .maxDepth(32)
            .onEnter { it == root || showHidden || !it.name.startsWith(".") }
        for (f in walker) {
            currentCoroutineContext().ensureActive()
            if (f == root) continue
            if (!showHidden && f.name.startsWith(".")) continue
            if (f.name.lowercase().contains(q)) {
                emit(f.toItem())
                count++
                if (count >= limit) break
            }
        }
    }.flowOn(Dispatchers.IO)

    fun props(items: List<FileItem>): PropInfo {
        var bytes = 0L
        var files = 0
        var folders = 0
        for (item in items) {
            for (f in item.file.walkTopDown().maxDepth(64)) {
                if (f.isDirectory) {
                    if (f != item.file) folders++
                } else {
                    files++
                    bytes += f.length()
                }
            }
        }
        return PropInfo(bytes, files, folders)
    }

    fun hash(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Orden "natural": archivo2 va antes que archivo10. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object Sorter {
    private val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }

    /** [pinned]: rutas fijadas arriba; van antes que todo lo demás, también antes que las carpetas. */
    fun sort(list: List<FileItem>, by: SortBy, asc: Boolean, pinned: Set<String> = emptySet()): List<FileItem> {
        val base: Comparator<FileItem> = when (by) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareBy<FileItem> { it.lastModified }.then(byName)
            SortBy.SIZE -> compareBy<FileItem> { if (it.isDirectory) it.childCount.toLong() else it.size }.then(byName)
            SortBy.TYPE -> compareBy<FileItem> { it.extension }.then(byName)
        }
        val ordered = if (asc) base else base.reversed()
        return list.sortedWith(
            compareByDescending<FileItem> { it.path in pinned }
                .then(compareByDescending<FileItem> { it.isDirectory })
                .then(ordered))
    }
}
