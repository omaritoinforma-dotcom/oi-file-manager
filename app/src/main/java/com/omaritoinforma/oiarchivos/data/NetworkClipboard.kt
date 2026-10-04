package com.omaritoinforma.oiarchivos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NetworkClip(
    val connection: Connection,
    val entries: List<RemoteEntry>,
    val move: Boolean,
    val parent: String = connection.root
)

object NetworkClipboard {
    var value by mutableStateOf<NetworkClip?>(null)
}
