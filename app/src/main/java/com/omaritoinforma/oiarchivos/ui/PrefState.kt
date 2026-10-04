package com.omaritoinforma.oiarchivos.ui

import androidx.compose.runtime.mutableStateOf

/** Un ajuste guardado que la interfaz observa: al cambiarlo se guarda y se vuelve a dibujar. */
class PrefState<T>(private val read: () -> T, private val write: (T) -> Unit) {
    private val state = mutableStateOf(read())

    var value: T
        get() = state.value
        set(v) {
            write(v)
            state.value = v
        }

    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value

    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, v: T) {
        value = v
    }

    /** Vuelve a leer el valor guardado, por ejemplo tras restaurar una copia de ajustes. */
    fun reload() {
        state.value = read()
    }
}
