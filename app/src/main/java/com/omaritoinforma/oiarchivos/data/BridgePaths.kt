package com.omaritoinforma.oiarchivos.data

import java.io.IOException

/** Lexical boundary; RemoteFileAgent also verifies each segment through the provider. */
object BridgePaths {
    fun within(root: String, path: String): String {
        fun normalized(value: String): String {
            if (!value.startsWith('/') || value.contains('\u0000') || value.contains('\\'))
                throw IOException("Ruta remota absoluta no válida")
            val parts = mutableListOf<String>()
            for (part in value.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw IOException("Ruta fuera de la raíz") else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
            return "/" + parts.joinToString("/")
        }
        val base = normalized(root)
        val target = normalized(if (path.isBlank()) base else path)
        if (base != "/" && target != base && !target.startsWith("$base/"))
            throw IOException("Ruta fuera de la conexión autorizada")
        return target
    }
}
