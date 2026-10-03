package com.omaritoinforma.oiarchivos.data
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NetworkClip(val connection: Connection, val entries: List<RemoteEntry>, val move: Boolean)
object NetworkClipboard { var value by mutableStateOf<NetworkClip?>(null) }
