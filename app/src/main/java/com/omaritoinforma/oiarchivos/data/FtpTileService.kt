package com.omaritoinforma.oiarchivos.data

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Mosaico de ajustes rápidos «Servidor FTP» (el acceso directo de ES para arrancarlo): enciende o
 * apaga el servidor FTP con la carpeta y las opciones elegidas la última vez en Compartir por red.
 */
class FtpTileService : TileService() {
    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        val running = ShareService.state.value?.mode == "FTP"
        if (running) ShareService.stop(this)
        else ShareService.start(this, Prefs(this).ftpRoot, "FTP")
        // El servicio avisa al mosaico cuando cambia; mientras, se muestra el estado pedido.
        qsTile?.apply {
            state = if (running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val info = ShareService.state.value?.takeIf { it.mode == "FTP" }
        tile.state = if (info != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = info?.url?.removePrefix("ftp://")?.trimEnd('/') ?: tr("Apagado")
        tile.updateTile()
    }

    companion object {
        /** Pide al sistema que vuelva a leer el estado del mosaico. */
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, FtpTileService::class.java)) }
        }
    }
}
