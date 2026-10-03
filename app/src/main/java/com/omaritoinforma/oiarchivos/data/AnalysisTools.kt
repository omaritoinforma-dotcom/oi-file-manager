package com.omaritoinforma.oiarchivos.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class SpaceAnalysis(
    val bytes: Long,
    val files: Int,
    val largest: List<File>,
    val folders: List<Pair<String, Long>>,
    val duplicates: List<List<File>>,
    val candidates: List<File>,
    val limited: Boolean
)

data class SearchFilter(
    val name: String = "",
    val extensions: Set<String> = emptySet(),
    val min: Long = 0,
    val max: Long = Long.MAX_VALUE,
    val since: Long = 0,
    val text: String = ""
)

object AnalysisTools {
    suspend fun analyze(
        root: File,
        duplicates: Boolean,
        report: (OpProgress) -> Unit
    ): SpaceAnalysis {
        val files = ArrayList<File>()
        val folders = LinkedHashMap<String, Long>()
        val candidates = ArrayList<File>()
        var bytes = 0L
        var limited = false
        for (f in SafeFiles.walk(root)) {
            currentCoroutineContext().ensureActive()
            if (f.isDirectory && f != root && f.list()?.isEmpty() == true) candidates += f
            if (!f.isFile) continue
            if (files.size >= 200000) {
                limited = true
                break
            }
            files += f
            bytes += f.length()
            val top = f.relativeTo(root).invariantSeparatorsPath.substringBefore('/')
            folders[top] = (folders[top] ?: 0) + f.length()
            if (f.extension.lowercase() in setOf("tmp", "temp", "bak") || f.length() == 0L)
                candidates += f
            if (files.size % 100 == 0)
                report(OpProgress("Analizando espacio", f.name, doneFiles = files.size))
        }
        val dupes = ArrayList<List<File>>()
        if (duplicates) {
            val groups =
                files
                    .filter { it.length() > 0 }
                    .groupBy { it.length() }
                    .values
                    .filter { it.size > 1 }
            val hashes = HashMap<String, MutableList<File>>()
            for (group in groups) for (file in group) {
                currentCoroutineContext().ensureActive()
                report(OpProgress("Buscando duplicados", file.name))
                val hash = digest(file)
                hashes.getOrPut(hash) { ArrayList() }.add(file)
            }
            dupes += hashes.values.filter { it.size > 1 }
        }
        return SpaceAnalysis(
            bytes,
            files.size,
            files.sortedByDescending { it.length() }.take(100),
            folders.entries.sortedByDescending { it.value }.take(50).map { it.key to it.value },
            dupes,
            candidates.take(1000),
            limited)
    }

    suspend fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun search(
        root: File,
        filter: SearchFilter,
        report: (OpProgress) -> Unit
    ): List<FileItem> {
        val found = ArrayList<FileItem>()
        var checked = 0
        for (f in SafeFiles.walk(root)) {
            currentCoroutineContext().ensureActive()
            if (f == root) continue
            if (++checked % 200 == 0) report(OpProgress("Buscando", f.name, doneFiles = checked))
            if (!f.name.contains(filter.name, ignoreCase = true) || f.lastModified() < filter.since)
                continue
            if (filter.extensions.isNotEmpty() && f.extension.lowercase() !in filter.extensions)
                continue
            if (f.isFile && f.length() !in filter.min..filter.max) continue
            if (filter.text.isNotEmpty()) {
                if (!f.isFile || f.length() > 8L * 1024 * 1024) continue
                val match =
                    runCatching {
                            f.bufferedReader().useLines { lines ->
                                lines.any { it.contains(filter.text, true) }
                            }
                        }
                        .getOrDefault(false)
                if (!match) continue
            }
            found += f.toItem()
            if (found.size >= 5000) break
        }
        return found
    }
}
