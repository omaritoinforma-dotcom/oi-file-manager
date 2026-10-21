package com.omaritoinforma.oiarchivos

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.omaritoinforma.oiarchivos.data.PendingUserActionStore
import com.omaritoinforma.oiarchivos.data.RootlessUserActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Private activity: remote commands cannot press the final Android confirmation. */
class UserActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Revisar solicitud"
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val space = (24 * resources.displayMetrics.density).toInt(); setPadding(space, space, space, space)
        }
        val details = TextView(this).apply { textSize = 18f; text = "Comprobando solicitud…" }
        body.addView(details)
        val button = Button(this).apply { text = "Continuar"; isEnabled = false }; body.addView(button)
        setContentView(ScrollView(this).apply { addView(body) })
        val id = intent.getStringExtra("requestId").orEmpty()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val request = RootlessUserActions.store(this@UserActionActivity).load(id)
                RootlessUserActions.requireAuthorization(this@UserActionActivity, request.action, request.remote)
                val description = if (request.action == "install") {
                    val info = packageManager.getPackageArchiveInfo(request.apk!!.path, 0)
                        ?: throw IOException("La APK pendiente no es válida")
                    if (request.packageName.isNotBlank() && request.packageName != info.packageName)
                        throw IOException("El paquete de la APK cambió")
                    "Instalar ${info.packageName}\nVersión: ${info.versionName.orEmpty()}\n\nSHA-256:\n${request.sha256}\n\nAndroid comprobará la APK y pedirá tu confirmación. Esta pantalla no significa que ya esté instalada."
                } else "Solicitar desinstalación de ${request.packageName}\n\nAndroid pedirá confirmación. Desinstalar puede eliminar los datos de esa aplicación."
                request to description
            } }
            result.onSuccess { (request, description) ->
                details.text = description; button.isEnabled = true
                button.text = if (request.action == "install") "Abrir instalador de Android" else "Abrir confirmación de Android"
                button.setOnClickListener {
                    runCatching {
                        RootlessUserActions.requireAuthorization(this@UserActionActivity, request.action, request.remote)
                        continueWithAndroid(request)
                    }.onFailure { details.text = it.message ?: "No se pudo abrir Android" }
                }
                body.addView(Button(this@UserActionActivity).apply {
                    text = "Descartar solicitud"
                    setOnClickListener { RootlessUserActions.store(this@UserActionActivity).discard(request.id); finish() }
                })
            }.onFailure { details.text = it.message ?: "No se pudo recuperar la solicitud" }
        }
    }

    private fun continueWithAndroid(request: PendingUserActionStore.Request) {
        if (request.action == "install") {
            if (!packageManager.canRequestPackageInstalls()) {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.installer", request.apk!!)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .apply { clipData = ClipData.newRawUri("APK pendiente", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${request.packageName}")))
        }
    }
}
