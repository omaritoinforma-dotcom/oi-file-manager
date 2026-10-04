package com.omaritoinforma.oiarchivos.data

/** Stable partition: pinning changes priority, while each group's existing order is preserved. */
object PinnedOrder {
    fun <T> first(items: List<T>, pinned: Set<String>, key: (T) -> String): List<T> {
        if (pinned.isEmpty()) return items
        val (first, rest) = items.partition { key(it) in pinned }
        return first + rest
    }
}
