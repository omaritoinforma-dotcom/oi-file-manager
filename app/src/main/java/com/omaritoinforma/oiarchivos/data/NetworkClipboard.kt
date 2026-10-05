package com.omaritoinforma.oiarchivos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NetworkClip(
    val connection: Connection,
    val entries: List<RemoteEntry>,
    val move: Boolean,
    /** Carpeta donde se eligieron los elementos; en las nubes las rutas son ids y no se puede deducir. */
    val parent: String
)

object NetworkClipboard {
    var value by mutableStateOf<NetworkClip?>(null)
}
