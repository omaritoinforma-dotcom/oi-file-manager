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

/** Una TV a la que enviar: un televisor DLNA o un Chromecast. */
sealed interface Tv {
    val name: String
    val host: String

    /** Identifica la TV en las listas. */
    val key: String
}

/** Lo que se está enviando a la TV; sigue aunque se cambie de pantalla. */
object CastSession {
    data class State(
        val renderer: Tv,
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

    /** Conexión con el Chromecast en uso; se usa siempre dentro de synchronized(castLock). */
    private var cast: CastV2.Client? = null
    private val castLock = Any()

    private fun <T> onCast(action: (CastV2.Client) -> T): T =
        synchronized(castLock) { action(cast ?: throw java.io.IOException(tr("Sin conexión con la TV"))) }

    /** Dirección de este teléfono en la red de [host] (sin enviar nada). */
    internal fun localAddressFor(host: InetAddress): String =
        DatagramSocket().use {
            it.connect(host, 9)
            it.localAddress.hostAddress ?: throw java.io.IOException(tr("Sin conexión con la TV"))
        }

    fun start(renderer: Tv, source: StreamServer.Source) {
        poll?.cancel()
        closeCast()
        state.value = State(renderer, source.name)
        scope.launch {
            try {
                val tv = InetAddress.getByName(renderer.host)
                val url =
                    StreamServer.castUrl(
                        source, localAddressFor(tv), tv.hostAddress ?: renderer.host)
                val mime = StreamServer.mime(source.name)
                when (renderer) {
                    is Dlna.Renderer -> {
                        Dlna.load(renderer, url, source.name, mime)
                        Dlna.play(renderer)
                    }
                    is CastV2.Device -> {
                        val client = CastV2.Client(renderer)
                        synchronized(castLock) { cast = client }
                        onCast {
                            it.launch()
                            it.load(url, source.name, mime)
                        }
                    }
                }
                state.value = State(renderer, source.name, playing = true, busy = false)
                follow(renderer)
            } catch (e: Exception) {
                closeCast()
                state.value = State(renderer, source.name, busy = false, error = e.message ?: tr("No se pudo enviar"))
            }
        }
    }

    private fun position(renderer: Tv): Pair<Long, Long>? =
        when (renderer) {
            is Dlna.Renderer -> Dlna.position(renderer)
            is CastV2.Device -> onCast { it.position() }
        }

    private fun follow(renderer: Tv) {
        poll =
            scope.launch {
                while (isActive) {
                    runCatching { position(renderer) }
                        .getOrNull()
                        ?.let { (at, total) ->
                            state.value = state.value?.copy(position = at, duration = total)
                        }
                    delay(1000)
                }
            }
    }

    private fun closeCast() {
        synchronized(castLock) {
            cast?.close()
            cast = null
        }
    }

    private fun command(update: (State) -> State, action: (Tv) -> Unit) {
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
        command({ it.copy(playing = !playing) }) { tv ->
            when (tv) {
                is Dlna.Renderer -> if (playing) Dlna.pause(tv) else Dlna.play(tv)
                is CastV2.Device -> onCast { if (playing) it.pause() else it.play() }
            }
        }
    }

    fun seek(seconds: Long) =
        command({ it.copy(position = seconds) }) { tv ->
            when (tv) {
                is Dlna.Renderer -> Dlna.seek(tv, seconds)
                is CastV2.Device -> onCast { it.seek(seconds) }
            }
        }

    /** Detiene la TV y deja de servirle el archivo. */
    fun stop() {
        val current = state.value ?: return
        poll?.cancel()
        state.value = null
        scope.launch {
            when (val tv = current.renderer) {
                is Dlna.Renderer -> runCatching { Dlna.stop(tv) }
                is CastV2.Device -> runCatching { onCast { it.stop() } }
            }
            closeCast()
            StreamServer.stopCast()
        }
    }
}
