package com.omaritoinforma.oiarchivos.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NetworkClip(
    val connection: Connection,
    val entries: List<RemoteEntry>,
    val move: Boolean,
    /** Folder the entries were selected in; cloud paths are ids, so it cannot be derived. */
    val parent: String
)

object NetworkClipboard {
    var value by mutableStateOf<NetworkClip?>(null)
}
