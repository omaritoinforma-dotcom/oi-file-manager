package com.omaritoinforma.oiarchivos.data

import java.io.IOException

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object Subtitles {
    fun parseSrt(source: String): List<SubtitleCue> {
        if (source.length > 2 * 1024 * 1024) throw IOException("Subtítulos demasiado grandes")
        val timing =
            Regex(
                "(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{3}).*")
        val cues =
            source
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .split(Regex("\\n[ \\t]*\\n+"))
                .filter { it.isNotBlank() }
                .map { block ->
                    val lines = block.lines()
                    val index = if (lines.first().trim().toIntOrNull() != null) 1 else 0
                    val match =
                        timing.matchEntire(lines.getOrNull(index)?.trim().orEmpty())
                            ?: throw IOException("Revisa los tiempos del archivo SRT")
                    fun time(first: Int): Long {
                        val h = match.groupValues[first].toLong()
                        val m = match.groupValues[first + 1].toLong()
                        val s = match.groupValues[first + 2].toLong()
                        val ms = match.groupValues[first + 3].toLong()
                        if (m > 59 || s > 59) throw IOException("Tiempo SRT no válido")
                        return ((h * 60 + m) * 60 + s) * 1000 + ms
                    }
                    val start = time(1)
                    val end = time(5)
                    val text = lines.drop(index + 1).joinToString("\n")
                    if (end <= start || text.isBlank() || text.length > 4096)
                        throw IOException("Subtítulo SRT no válido")
                    SubtitleCue(start, end, text)
                }
        if (cues.size > 20000) throw IOException("Demasiados subtítulos")
        return cues.sortedBy { it.startMs }
    }

    fun textAt(cues: List<SubtitleCue>, positionMs: Long): String =
        cues
            .filter { positionMs >= it.startMs && positionMs < it.endMs }
            .joinToString("\n") { it.text }
}
