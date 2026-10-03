package com.omaritoinforma.oiarchivos.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.AdditionalCloudAuth
import com.omaritoinforma.oiarchivos.data.Connection
import com.omaritoinforma.oiarchivos.data.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdditionalCloudLoginDialog(c: Connection, onDone: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var approval by remember { mutableStateOf<AdditionalCloudAuth.DeviceApproval?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(c.protocol == Protocol.BAIDU) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(c.id) {
        if (c.protocol == Protocol.BAIDU) {
            try {
                val device = withContext(Dispatchers.IO) { AdditionalCloudAuth.baiduStart(c) }
                approval = device
                withContext(Dispatchers.IO) { AdditionalCloudAuth.baiduFinish(context, c, device) }
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "No se pudo autorizar la cuenta"
            } finally {
                busy = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Entrar en ${c.protocol.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.protocol == Protocol.BAIDU) {
                    Text(
                        "Autoriza la cuenta en Baidu con este código. El acceso se guardará cuando confirmes allí.")
                    approval?.let { device ->
                        Text(device.userCode, style = MaterialTheme.typography.headlineMedium)
                        Row {
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(device.userCode)) }) {
                                    Text("Copiar código")
                                }
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(device.url)))
                                }) {
                                    Text("Abrir Baidu")
                                }
                        }
                    }
                } else {
                    Text(
                        "${c.user}\nLa contraseña se usa para autorizar esta conexión y no se guarda.")
                    OutlinedTextField(
                        password,
                        { password = it },
                        label = { Text("Contraseña de SugarSync") },
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (c.protocol == Protocol.SUGARSYNC)
                TextButton(
                    enabled = !busy && password.isNotBlank(),
                    onClick = {
                        val entered = password
                        password = ""
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    AdditionalCloudAuth.sugarLogin(context, c, entered)
                                }
                                onDone()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "No se pudo entrar"
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                        Text("Entrar")
                    }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } })
}
