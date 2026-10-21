@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.omaritoinforma.oiarchivos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.theme.OiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class RootlessDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var result by remember {mutableStateOf(RootlessDebugger.status().toString(2))}
            var selected by remember {mutableStateOf("memory")}
            var pkg by remember {mutableStateOf("")}
            var value by remember {mutableStateOf("")}
            var xy by remember {mutableStateOf("0,0,0,0")}
            var menu by remember {mutableStateOf(false)}
            var busy by remember {mutableStateOf(false)}
            var pending by remember { mutableStateOf<List<String>>(emptyList()) }
            val scope=rememberCoroutineScope()
            LaunchedEffect(result) {
                pending = withContext(Dispatchers.IO) { RootlessUserActions.store(this@RootlessDebugActivity).pendingIds() }
            }
            val actions=listOf("system_list","system_stat","system_read","memory","battery","activity","package","appops","network","display","storage","processes","list_packages","crashes","logcat",
                "force_stop","clear_data","disable","enable","install","uninstall","grant","revoke","set_appop","tap","swipe","keyevent","text","screenshot")
            val labels=listOf("Listar rutas del sistema (solo lectura)","Datos de ruta del sistema","Leer configuración pública (máx. 32 KiB)","Memoria","Batería","Actividad de aplicaciones","Inspeccionar paquete","Consultar AppOps","Red","Pantalla","Almacenamiento","Procesos","Paquetes instalados","Fallos recientes","Registros del sistema",
                "Detener aplicación","Borrar datos de aplicación","Deshabilitar aplicación","Habilitar aplicación","Instalar APK","Desinstalar para este usuario","Conceder permiso","Revocar permiso","Cambiar AppOps","Tocar pantalla","Deslizar","Enviar tecla","Introducir texto","Guardar captura").let {actions.zip(it).toMap()}
            fun runCommand() {
                busy=true
                scope.launch {
                    result=withContext(Dispatchers.IO) {
                        runCatching {
                            val points=xy.split(',').map {it.trim().toIntOrNull() ?: 0}
                            val args=JSONObject().put("action",selected).put("packageName",pkg.trim()).put("value",value)
                            listOf("x","y","x2","y2").forEachIndexed {i,key->args.put(key,points.getOrElse(i){0})}
                            RootlessDebugger.execute(this@RootlessDebugActivity,"debug_run",args,false).toString(2)
                        }.getOrElse {it.message ?: "No se pudo ejecutar"}
                    }
                    busy=false
                }
            }
            OiTheme(dark=androidx.compose.foundation.isSystemInDarkTheme()) {
                Scaffold(topBar={TopAppBar(title={Text("Depurador sin root")},navigationIcon={TextButton(onClick={finish()}){Text("Volver")}})}) {padding->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Sin Shizuku puedes consultar datos públicos, firmas y permisos de las apps, y solicitar instalación o desinstalación con confirmación de Android. Memoria, actividad y procesos tienen un alcance limitado a OI Archivos. Las órdenes avanzadas siguen requiriendo ADB/Shizuku.")
                        if (pending.isNotEmpty()) {
                            Text("Solicitudes pendientes")
                            pending.forEach { id ->
                                OutlinedButton(onClick = {
                                    startActivity(Intent(this@RootlessDebugActivity, UserActionActivity::class.java).putExtra("requestId", id))
                                }) { Text("Revisar solicitud ${id.take(8)}") }
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={result=runCatching {RootlessDebugger.requestPermission();"Solicitud enviada a Shizuku"}.getOrElse {it.message.orEmpty()}}){Text("Autorizar Shizuku")}
                            OutlinedButton(onClick={result=RootlessDebugger.status().toString(2)}){Text("Estado")}
                        }
                        TextButton(onClick={
                            val open=packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                            startActivity(open ?: Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/download/")))
                        }){Text("Abrir o instalar Shizuku")}
                        TextButton(onClick={startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))}){Text("Opciones de desarrollador de Android")}
                        Button(onClick={
                            busy=true
                            scope.launch {
                                result=withContext(Dispatchers.IO) {runCatching {RootlessDebugger.execute(this@RootlessDebugActivity,"debug_device_info",JSONObject(),false).toString(2)}.getOrElse {it.message.orEmpty()}}
                                busy=false
                            }
                        },enabled=!busy){Text("Diagnóstico básico — sin Shizuku")}
                        Box {
                            OutlinedButton(onClick={menu=true}){Text(labels[selected].orEmpty())}
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {actions.forEach {action->DropdownMenuItem(text={Text(labels[action].orEmpty())},onClick={selected=action;menu=false})}}
                        }
                        OutlinedTextField(pkg,{pkg=it},label={Text("Paquete, cuando corresponda")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        OutlinedTextField(value,{value=it},label={Text(if(selected.startsWith("system_"))"Ruta absoluta (ej. /system/build.prop)" else "Valor: permiso, ruta, texto o código de tecla")},modifier=Modifier.fillMaxWidth())
                        if(selected in setOf("tap","swipe"))OutlinedTextField(xy,{xy=it},label={Text("Coordenadas: x,y,x2,y2")},modifier=Modifier.fillMaxWidth())
                        Button(onClick={
                            if(selected=="clear_data")android.app.AlertDialog.Builder(this@RootlessDebugActivity)
                                .setTitle("Confirmar $selected").setMessage("Se modificarán o eliminarán los datos de $pkg. Esta acción no se puede deshacer desde OI Archivos.")
                                .setNegativeButton("Cancelar",null).setPositiveButton("Ejecutar"){_,_->runCommand()}.show()
                            else runCommand()
                        },enabled=!busy){Text(if(busy)"Ejecutando…" else "Ejecutar")}
                        SelectionContainer {Text(result)}
                    }
                }
            }
        }
    }
}
