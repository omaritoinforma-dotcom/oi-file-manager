package com.omaritoinforma.oiarchivos.data

import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lo que se está enviando a la TV; sigue aunque se cambie de pantalla. */
object CastSession {
    data class State(
        val renderer: Dlna.Renderer,
        val title: String,
        val playing: Boolean = false,
        val position: Long = 0,
        val duration: Long = 0,
        val busy: Boolean = true,
        val error: String? = null
    )

    val state = MutableStateFlow<State?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null

    /** Dirección de este teléfono en la red de [host] (sin enviar nada). */
    internal fun localAddressFor(host: InetAddress): String =
        DatagramSocket().use {
            it.connect(host, 9)
            it.localAddress.hostAddress ?: throw java.io.IOException(tr("Sin conexión con la TV"))
        }

    fun start(renderer: Dlna.Renderer, source: StreamServer.Source) {
        poll?.cancel()
        state.value = State(renderer, source.name)
        scope.launch {
            try {
                val tv = InetAddress.getByName(renderer.host)
                val url =
                    StreamServer.castUrl(
                        source, localAddressFor(tv), tv.hostAddress ?: renderer.host)
                Dlna.load(renderer, url, source.name, StreamServer.mime(source.name))
                Dlna.play(renderer)
                state.value = State(renderer, source.name, playing = true, busy = false)
                follow(renderer)
            } catch (e: Exception) {
                state.value = State(renderer, source.name, busy = false, error = e.message ?: tr("No se pudo enviar"))
            }
        }
    }

    private fun follow(renderer: Dlna.Renderer) {
        poll =
            scope.launch {
                while (isActive) {
                    runCatching { Dlna.position(renderer) }
                        .getOrNull()
                        ?.let { (at, total) ->
                            state.value = state.value?.copy(position = at, duration = total)
                        }
                    delay(1000)
                }
            }
    }

    private fun command(update: (State) -> State, action: (Dlna.Renderer) -> Unit) {
        val current = state.value ?: return
        scope.launch {
            try {
                action(current.renderer)
                state.value = state.value?.let(update)?.copy(error = null)
            } catch (e: Exception) {
                state.value = state.value?.copy(error = e.message ?: tr("La TV no respondió"))
            }
        }
    }

    fun pauseOrResume() {
        val playing = state.value?.playing ?: return
        command({ it.copy(playing = !playing) }) { if (playing) Dlna.pause(it) else Dlna.play(it) }
    }

    fun seek(seconds: Long) = command({ it.copy(position = seconds) }) { Dlna.seek(it, seconds) }

    /** Detiene la TV y deja de servirle el archivo. */
    fun stop() {
        val current = state.value ?: return
        poll?.cancel()
        state.value = null
        scope.launch {
            runCatching { Dlna.stop(current.renderer) }
            StreamServer.stopCast()
        }
    }
}
