package com.omaritoinforma.oiarchivos.data

enum class GestureAction(val label: String) {
    NONE("Sin acción"), UP("Carpeta superior"), HOME("Inicio"), REFRESH("Actualizar"),
    NEXT_TAB("Pestaña siguiente"), PREVIOUS_TAB("Pestaña anterior"), NEW_TAB("Nueva pestaña"),
    HIDDEN("Mostrar / ocultar archivos ocultos"), SELECT_ALL("Seleccionar todo")
}
