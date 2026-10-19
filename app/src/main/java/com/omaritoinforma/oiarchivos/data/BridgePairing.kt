package com.omaritoinforma.oiarchivos.data

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import com.omaritoinforma.oiarchivos.util.Perms
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object BridgePairing {
    fun offer(activity: Activity, intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "oiarchivos" || uri.host != "pair") return
        val ticket = uri.getQueryParameter("ticket") ?: return
        if (!Regex("[a-f0-9]{64}").matches(ticket)) return
        intent.data = null // Don't show the same prompt again on recreation.
        AlertDialog.Builder(activity).setTitle("Conectar OI Archivos con ChatGPT")
            .setMessage("Se habilitarán lectura, escritura, papelera, borrado de archivos, conexiones guardadas y diagnóstico remoto. La depuración ADB requiere además autorizar Shizuku sin root. Puedes desconectarlo desde Ajustes o la notificación.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Conectar") { _, _ ->
                if (!Perms.hasStorage(activity)) {
                    Toast.makeText(activity,"Concede acceso a los archivos y vuelve a abrir el enlace",Toast.LENGTH_LONG).show()
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:" + activity.packageName)))
                    } else activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),90)
                    return@setPositiveButton
                }
                Thread({
                    val result = runCatching {
                        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                            .callTimeout(20,TimeUnit.SECONDS).build()
                        val request = Request.Builder()
                            .url("https://test.omaritoinforma.com/oi-android-bridge/index.php/device/pair")
                            .post(JSONObject().put("ticket",ticket).toString().toRequestBody("application/json".toMediaType())).build()
                        val data = client.newCall(request).execute().use {
                            if (!it.isSuccessful) throw java.io.IOException("El enlace caducó o ya fue utilizado")
                            JSONObject(it.body?.string().orEmpty())
                        }
                        val config = BridgeConfig(activity)
                        config.endpoint = data.getString("endpoint")
                        config.token = data.getString("deviceToken")
                        check(config.valid()) { "Configuración no válida" }
                        config.allowChanges = true; config.allowTrash = true
                        config.allowRemote = true; config.allowDelete = true
                        config.allowDebug = true
                    }
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        if (result.isSuccess) {
                            RemoteBridgeService.start(activity)
                            Toast.makeText(activity,"Conexión iniciada. Revisa la notificación de OI Archivos",Toast.LENGTH_LONG).show()
                        } else Toast.makeText(activity,result.exceptionOrNull()?.message ?: "No se pudo emparejar",Toast.LENGTH_LONG).show()
                    }
                },"oi-pairing").start()
            }.show()
    }
}
