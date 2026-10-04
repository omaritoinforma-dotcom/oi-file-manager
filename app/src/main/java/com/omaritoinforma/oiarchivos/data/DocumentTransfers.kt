package com.omaritoinforma.oiarchivos.data

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DocumentTransferResult(val destinationIds: List<String>, val moved: Boolean)

/** Whole-tree snapshots and full read-back checks precede every move's deletion phase. */
object DocumentTransfers {
    private data class Node(
        val info: DocumentInfo,
        val fingerprint: DocumentFingerprint?,
        val children: List<Node>,
        var targetId: String? = null
    )

    suspend fun transfer(
        source: DocumentStore,
        ids: List<String>,
        destination: DocumentStore,
        parentId: String,
        move: Boolean,
        sameDocument: (String, String) -> Boolean = { a, b -> a == b },
        report: (OpProgress) -> Unit = {},
        maxFileSize: Long = Long.MAX_VALUE
    ): DocumentTransferResult {
        val parent = destination.stat(parentId)
        if (!parent.directory || !parent.canCreate)
            throw IOException("El destino no permite crear archivos. Elige otra carpeta.")
        var count = 0
        val visited = hashSetOf<String>()
        suspend fun snapshot(id: String, depth: Int = 0): Node {
            currentCoroutineContext().ensureActive()
            if (depth > 128 || ++count > 100000) throw IOException("La selección supera los límites de carpetas o archivos")
            if (!visited.add(id)) throw IOException("El proveedor devolvió documentos repetidos o un ciclo")
            val info = source.stat(id)
            SafeFiles.requireName(info.name)
            if (sameDocument(id, parentId)) throw IOException("No puedes copiar una carpeta dentro de sí misma")
            if (!info.readable) throw IOException("Sin permiso de lectura para ${info.name}")
            if (move && !info.canDelete) throw IOException("${info.name} no permite mover; usa Copiar para conservar el original")
            if (info.directory) {
                val children = source.children(id).map { snapshot(it.id, depth + 1) }
                return Node(info, null, children)
            }
            return Node(info, DocumentTransactions.fingerprint(source, id, maxFileSize), emptyList())
        }
        val roots = ids.distinct().map { snapshot(it) }
        if (roots.isEmpty()) throw IOException("Selecciona al menos un archivo")

        fun unique(parent: String, name: String): String {
            val existing = destination.children(parent).map { it.name }.toSet()
            if (name !in existing) return name
            val base = name.substringBeforeLast('.', name)
            val extension = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var n = 1
            while ("$base ($n)$extension" in existing) n++
            return "$base ($n)$extension"
        }

        fun matches(actual: DocumentFingerprint, expected: DocumentFingerprint) =
            actual.size == expected.size && actual.sha256 == expected.sha256 &&
                (expected.modified == 0L || actual.modified == expected.modified)

        suspend fun checkSource(node: Node) {
            val now = source.stat(node.info.id)
            if (now.name != node.info.name || now.directory != node.info.directory)
                throw IOException("El original cambió: ${node.info.name}. Se conserva sin borrar.")
            if (node.info.directory) {
                val expected = node.children.map { it.info.id to it.info.name }.toSet()
                val actual = source.children(node.info.id).map { it.id to it.name }.toSet()
                if (actual != expected) throw IOException("La carpeta original cambió; se conserva sin borrar")
                node.children.forEach { checkSource(it) }
            } else if (!matches(DocumentTransactions.fingerprint(source, now.id, maxFileSize), node.fingerprint!!))
                throw IOException("El original cambió: ${node.info.name}. Se conserva sin borrar.")
        }

        suspend fun copy(node: Node, parent: String) {
            currentCoroutineContext().ensureActive()
            val name = unique(parent, node.info.name)
            if (node.info.directory) {
                val target = destination.createDirectory(parent, name)
                node.targetId = target
                node.children.forEach { copy(it, target) }
                return
            }
            val expected = node.fingerprint!!
            checkSource(node)
            val target = destination.createFile(parent, name, "application/octet-stream")
            node.targetId = target
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                var done = 0L
                source.openRead(node.info.id).use { input ->
                    destination.openWriteNew(target).use { output ->
                        val buffer = ByteArray(131072)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (done + n > expected.size || done + n > maxFileSize)
                                throw IOException("El origen aumentó durante la copia; se conserva sin borrar")
                            output.write(buffer, 0, n)
                            hash.update(buffer, 0, n)
                            done += n
                            report(OpProgress("Copiando documentos", node.info.name, done, expected.size))
                        }
                        output.flush()
                    }
                }
                val digest = hash.digest().joinToString("") { "%02x".format(it) }
                if (done != expected.size || digest != expected.sha256)
                    throw IOException("El origen cambió durante la copia; se conserva sin borrar")
                val readBack = DocumentTransactions.fingerprint(destination, target, maxFileSize)
                if (readBack.size != expected.size || readBack.sha256 != expected.sha256)
                    throw IOException("El proveedor no confirmó una copia completa; el original se conserva")
                checkSource(node)
            } catch (failure: Exception) {
                // Only this newly-created incomplete file is eligible for cleanup.
                runCatching { destination.delete(target) }
                throw failure
            }
        }

        suspend fun checkDestination(node: Node) {
            val target = node.targetId ?: throw IOException("Copia sin completar")
            if (node.info.directory) {
                val expected = node.children.mapNotNull { it.targetId }.toSet()
                if (destination.children(target).map { it.id }.toSet() != expected)
                    throw IOException("La carpeta de destino cambió; el original se conserva")
                node.children.forEach { checkDestination(it) }
            } else {
                val now = DocumentTransactions.fingerprint(destination, target, maxFileSize)
                if (now.size != node.fingerprint!!.size || now.sha256 != node.fingerprint.sha256)
                    throw IOException("La copia de destino cambió; el original se conserva")
                if (move) destination.requireDurable(target)
            }
        }

        roots.forEach { copy(it, parentId) }
        roots.forEach { checkSource(it); checkDestination(it) }
        if (move) {
            suspend fun remove(node: Node) {
                currentCoroutineContext().ensureActive()
                if (node.info.directory) {
                    node.children.forEach { remove(it) }
                    if (source.children(node.info.id).isNotEmpty())
                        throw IOException("La carpeta tiene documentos nuevos; se conserva sin borrar")
                } else {
                    checkSource(node)
                    checkDestination(node)
                }
                if (!source.delete(node.info.id))
                    throw IOException("Copiado y verificado; no se pudo eliminar ${node.info.name}")
            }
            roots.forEach { remove(it) }
        }
        return DocumentTransferResult(roots.mapNotNull { it.targetId }, move)
    }
}
