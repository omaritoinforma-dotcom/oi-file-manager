package com.omaritoinforma.oiarchivos.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("oi_archivos", Context.MODE_PRIVATE)

    var viewMode: ViewMode
        get() = enumOr(sp.getString("view_mode", null), ViewMode.DETAILS)
        set(v) = sp.edit().putString("view_mode", v.name).apply()

    var sortBy: SortBy
        get() = enumOr(sp.getString("sort_by", null), SortBy.NAME)
        set(v) = sp.edit().putString("sort_by", v.name).apply()

    var ascending: Boolean
        get() = sp.getBoolean("ascending", true)
        set(v) = sp.edit().putBoolean("ascending", v).apply()

    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit().putBoolean("show_hidden", v).apply()

    var useTrash: Boolean
        get() = sp.getBoolean("use_trash", true)
        set(v) = sp.edit().putBoolean("use_trash", v).apply()

    var themeMode: ThemeMode
        get() = enumOr(sp.getString("theme", null), ThemeMode.SYSTEM)
        set(v) = sp.edit().putString("theme", v.name).apply()

    var history: List<String>
        get() = sp.getString("history", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("history", v.joinToString("\n")).apply()

    var gridSize: Int
        get() = sp.getInt("grid_size", 96)
        set(v) = sp.edit().putInt("grid_size", v.coerceIn(72,160)).apply()

    var bookmarks: List<String>
        get() = sp.getString("bookmarks", "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("bookmarks", v.joinToString("\n")).apply()
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
