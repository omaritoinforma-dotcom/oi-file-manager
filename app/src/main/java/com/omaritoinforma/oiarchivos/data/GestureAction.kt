package com.omaritoinforma.oiarchivos.data

enum class GestureAction(private val labelEs: String) {
    NONE(trKey("Sin acción")), UP(trKey("Carpeta superior")), HOME(trKey("Inicio")), REFRESH(trKey("Actualizar")),
    NEXT_TAB(trKey("Pestaña siguiente")), PREVIOUS_TAB(trKey("Pestaña anterior")), NEW_TAB(trKey("Nueva pestaña")),
    HIDDEN(trKey("Mostrar / ocultar archivos ocultos")), SELECT_ALL(trKey("Seleccionar todo"));

    val label: String
        get() = tr(labelEs)
}
