package com.omaritoinforma.oiarchivos.util

import android.os.Environment
import java.io.File

data class PathSegment(val label: String, val path: String)

object PathUtil {
    val internalRoot: String get() = Environment.getExternalStorageDirectory().absolutePath
    private val sdRegex = Regex("^/storage/(?!emulated|self)[^/]+")

    fun segments(path: String): List<PathSegment> {
        val out = mutableListOf<PathSegment>()
        val internal = internalRoot
        val sd = sdRegex.find(path)?.value
        val base: String
        when {
            path == internal || path.startsWith("$internal/") -> {
                base = internal
                out += PathSegment("Interno", internal)
            }
            sd != null -> {
                base = sd
                out += PathSegment("SD", sd)
            }
            else -> {
                base = ""
                out += PathSegment("/", "/")
            }
        }
        var acc = base
        path.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { part ->
            acc = "$acc/$part"
            out += PathSegment(part, acc)
        }
        return out
    }

    fun displayName(path: String): String = when {
        path == internalRoot -> "Almacenamiento interno"
        path == "/" -> "Raíz del sistema"
        sdRegex.matches(path) -> "Tarjeta SD"
        else -> File(path).name.ifEmpty { path }
    }
}
