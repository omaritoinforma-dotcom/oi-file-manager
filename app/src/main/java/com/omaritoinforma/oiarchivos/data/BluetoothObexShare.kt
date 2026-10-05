package com.omaritoinforma.oiarchivos.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Comparte una carpeta por Bluetooth con OBEX FTP: los equipos emparejados (otro teléfono, un PC)
 * la exploran con su explorador Bluetooth. La conexión es la segura de RFCOMM, así que Android
 * exige que los equipos estén emparejados.
 */
@SuppressLint("MissingPermission") // Se comprueba abajo antes de usar Bluetooth.
class BluetoothObexShare(ctx: Context, private val root: File, private val writable: Boolean) : AutoCloseable {
    private val server: BluetoothServerSocket
    private val sessions = Collections.synchronizedSet(HashSet<BluetoothSocket>())
    val deviceName: String

    init {
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw IOException(tr("Concede el permiso de dispositivos cercanos para usar Bluetooth"))
        val adapter =
            ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException(tr("Este dispositivo no tiene Bluetooth"))
        if (!adapter.isEnabled) throw IOException(tr("Activa Bluetooth en los ajustes de Android"))
        deviceName = adapter.name ?: Build.MODEL
        server = adapter.listenUsingRfcommWithServiceRecord("OBEX File Transfer", UUID.fromString("00001106-0000-1000-8000-00805f9b34fb"))
        thread(isDaemon = true, name = "obex-servidor") {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sessions += socket
                thread(isDaemon = true, name = "obex-sesion") {
                    try {
                        socket.use { ObexFtpServer(root, writable).serve(it.inputStream, it.outputStream) }
                    } catch (_: IOException) {
                        // El otro equipo cortó la conexión.
                    } finally {
                        sessions -= socket
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        sessions.toList().forEach { runCatching { it.close() } }
    }
}
