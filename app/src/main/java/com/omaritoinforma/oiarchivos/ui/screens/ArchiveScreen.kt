package com.omaritoinforma.oiarchivos.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omaritoinforma.oiarchivos.data.*
import com.omaritoinforma.oiarchivos.ui.MainViewModel
import com.omaritoinforma.oiarchivos.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ArchiveScreen(vm: MainViewModel, path: String) {
    val file=remember(path){File(path)};var password by remember(path){mutableStateOf("")};var entered by remember(path){mutableStateOf("")}
    var entries by remember(path){mutableStateOf<List<ArchiveEntry>>(emptyList())};var error by remember(path){mutableStateOf<String?>(null)}
    var loading by remember(path){mutableStateOf(true)};var prefix by remember(path){mutableStateOf("")}
    LaunchedEffect(path,entered){loading=true;error=null;withContext(Dispatchers.IO){runCatching{ArchiveTools.list(file,entered)}}.onSuccess{entries=it}.onFailure{error=it.message};loading=false}
    ToolPage(file.name,vm) { pad -> Column(Modifier.fillMaxSize().padding(pad)) {
        OutlinedTextField(password,{password=it},label={Text("Contraseña (si corresponde)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(12.dp))
        Row { TextButton(onClick={entered=password}){Text("Abrir")};TextButton(onClick={vm.extract(file.toItem(),password)}){Text("Extraer en carpeta nueva")};if(prefix.isNotEmpty())TextButton(onClick={prefix=prefix.trimEnd('/').substringBeforeLast('/',"").let{if(it.isEmpty())"" else "$it/"}}){Text("Subir")}}
        Text(prefix.ifEmpty{"Contenido del comprimido"},Modifier.padding(12.dp))
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(error!=null)Text(error!!,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error)
        val children=remember(entries,prefix){entries.filter{it.name.replace('\\','/').startsWith(prefix)}.mapNotNull { e->
            val rest=e.name.replace('\\','/').removePrefix(prefix);if(rest.isEmpty())null else if(rest.contains('/'))ArchiveEntry(prefix+rest.substringBefore('/')+"/",0,true)else e
        }.distinctBy{it.name}.sortedWith(compareByDescending<ArchiveEntry>{it.directory}.thenBy{it.name})}
        LazyColumn { items(children,key={it.name}) { entry -> ListItem(headlineContent={Text(entry.name.removePrefix(prefix).trimEnd('/'))},supportingContent={Text(if(entry.directory)"Carpeta"else if(entry.size<0)"Tamaño desconocido"else formatSize(entry.size))},modifier=Modifier.clickable {
            if(entry.directory)prefix=entry.name else if(file.extension.lowercase() in setOf("zip","jar","apks")) {
                vm.runTask("Abriendo archivo del ZIP") { report ->
                    if(entry.size>64L*1024*1024)throw java.io.IOException("Extrae primero los archivos de más de 64 MB")
                    val dir=File(vm.getApplication<android.app.Application>().cacheDir,"archive-preview").apply{mkdirs()}
                    val coroutineContext=kotlinx.coroutines.currentCoroutineContext()
                    val target=FileOps.uniqueName(dir,entry.name.replace('\\','/').substringAfterLast('/'))
                    net.lingala.zip4j.ZipFile(file,password.toCharArray()).use{zip->val h=zip.getFileHeader(entry.name) ?: throw java.io.IOException("No existe la entrada");SafeFiles.writeAtomic(target){temp->zip.getInputStream(h).use{input->temp.outputStream().use{out->val buf=ByteArray(65536);var done=0L;while(true){coroutineContext.ensureActive();val n=input.read(buf);if(n<0)break;done+=n;if(done>64L*1024*1024)throw java.io.IOException("Vista previa demasiado grande");out.write(buf,0,n);report(OpProgress("Abriendo",target.name,done,entry.size))}}}}}
                    withContext(Dispatchers.Main){vm.openFile(target.path)}
                    OperationResult(null)
                }
            }else vm.toast("Usa «Extraer en carpeta nueva» para abrir este archivo")
        }) } }
    } }
}
