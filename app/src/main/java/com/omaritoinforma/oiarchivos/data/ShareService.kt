package com.omaritoinforma.oiarchivos.data

import android.app.*
import android.content.*
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/** Servers are opt-in, authenticated, restricted to one user-selected folder, and foreground-owned. */
data class ShareInfo(val url:String,val user:String,val password:String,val root:String,val mode:String)
class ShareService:Service(){
    private var http:LocalHttp?=null;private var ftp:LocalFtp?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        TransferService.createChannel(this)
        val stop=PendingIntent.getService(this,22,Intent(this,ShareService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(22,NotificationCompat.Builder(this,"transfers").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Compartir archivos por red").setContentText("Servidor activo · toca Detener al terminar").setOngoing(true).addAction(0,"Detener",stop).build())
        try{
            http?.stop();ftp?.close()
            val root=File(intent?.getStringExtra("root") ?: throw IOException("Elige una carpeta")).canonicalFile
            if(!root.isDirectory||!root.canRead())throw IOException("La carpeta no se puede leer")
            val address=localAddress() ?: throw IOException("Conéctate a una red Wi-Fi local")
            val password=Base64.encodeToString(ByteArray(18).apply{SecureRandom().nextBytes(this)},Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
            val mode=intent.getStringExtra("mode") ?: "HTTP"
            val port=if(mode=="FTP"){ftp=LocalFtp(root,address,password).apply{start()};ftp!!.port}else{http=LocalHttp(root,address,password).apply{start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)};http!!.listeningPort}
            state.value=ShareInfo("${if(mode=="FTP")"ftp"else"http"}://$address:$port/","oi",password,root.path,mode)
            error.value=null
        }catch(e:Exception){error.value=e.message;stopSelf()}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){stopSelf()}
    override fun onDestroy(){http?.stop();ftp?.close();state.value=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
    companion object{
        val state=MutableStateFlow<ShareInfo?>(null);val error=MutableStateFlow<String?>(null)
        fun start(ctx:Context,root:String,mode:String){ContextCompat.startForegroundService(ctx,Intent(ctx,ShareService::class.java).putExtra("root",root).putExtra("mode",mode))}
        fun stop(ctx:Context){ctx.stopService(Intent(ctx,ShareService::class.java))}
        private fun localAddress():String?=Collections.list(NetworkInterface.getNetworkInterfaces()).sortedBy{if(it.name.startsWith("wlan"))0 else 1}.flatMap{Collections.list(it.inetAddresses)}.firstOrNull{it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress}?.hostAddress
    }
}

private fun within(root:File,path:String):File{
    val target=if(path.isBlank()||path=="/")root else SafeFiles.archiveTarget(root,path.trimStart('/'))
    if(target!=root&&!target.canonicalPath.startsWith(root.canonicalPath+File.separator))throw IOException("Ruta fuera de la carpeta compartida")
    return target
}
private fun html(value:String)=value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
private class LocalHttp(private val root:File,address:String,private val password:String):NanoHTTPD(address,0){
    private val authorization="Basic "+Base64.encodeToString("oi:$password".toByteArray(),Base64.NO_WRAP)
    override fun serve(session:IHTTPSession):Response{
        if(!MessageDigest.isEqual(session.headers["authorization"].orEmpty().toByteArray(),authorization.toByteArray()))return newFixedLengthResponse(Response.Status.UNAUTHORIZED,"text/plain","Acceso protegido").apply{addHeader("WWW-Authenticate","Basic realm=\"OI Archivos\"")}
        return try{
            val file=within(root,session.uri)
            if(session.method==Method.POST){
                val length=session.headers["content-length"]?.toLongOrNull() ?: throw IOException("Falta el tamaño de la subida")
                if(length>1024L*1024*1024||length<0)throw IOException("Máximo 1 GB por subida desde el navegador")
                if(!file.isDirectory)throw IOException("El destino no es una carpeta")
                if(session.parms["csrf"]!=password)throw IOException("Formulario no autorizado")
                val body=HashMap<String,String>();session.parseBody(body)
                val uploaded=body["file"] ?: throw IOException("Elige un archivo")
                val name=session.parms["file"]?.replace('\\','/')?.substringAfterLast('/') ?: "archivo"
                SafeFiles.requireName(name)
                val target=FileOps.uniqueName(file,name)
                SafeFiles.writeAtomic(target){part->File(uploaded).inputStream().use{input->part.outputStream().use{input.copyTo(it)}}}
                return newFixedLengthResponse(Response.Status.REDIRECT,"text/plain","Subido").apply{addHeader("Location",session.uri)}
            }
            if(session.method!=Method.GET && session.method!=Method.HEAD)return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,"text/plain","Método no permitido")
            if(!file.exists())return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","No existe")
            if(file.isFile){val mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream";return newFixedLengthResponse(Response.Status.OK,mime,file.inputStream(),file.length()).apply{addHeader("Content-Disposition","attachment; filename*=UTF-8''${encode(file.name)}");addHeader("X-Content-Type-Options","nosniff")}}
            val base=if(file==root)"/" else "/"+file.relativeTo(root).invariantSeparatorsPath.trimEnd('/')+"/"
            val listing=(file.listFiles() ?: emptyArray()).filter{!Files.isSymbolicLink(it.toPath())}.sortedWith(compareByDescending<File>{it.isDirectory}.thenBy{it.name}).joinToString(""){child->"<li><a href=\"${html(base+encode(child.name))}\">${html(child.name)}${if(child.isDirectory)"/"else""}</a></li>"}
            val parent=if(file==root)""else"<p><a href=\"../\">Carpeta superior</a></p>"
            val content="<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>OI Archivos</title><h1>OI Archivos</h1><p>${html(base)}</p>$parent<ul>$listing</ul><form method=post enctype=multipart/form-data action='${html(base)}?csrf=${encode(password)}'><input type=file name=file required><button>Subir archivo (máx. 1 GB)</button></form>"
            newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8",content).apply{addHeader("Cache-Control","no-store");addHeader("X-Frame-Options","DENY");addHeader("Content-Security-Policy","default-src 'none'; form-action 'self'; frame-ancestors 'none'");addHeader("Referrer-Policy","no-referrer")}
        }catch(e:Exception){newFixedLengthResponse(Response.Status.BAD_REQUEST,"text/plain",e.message ?: "Error")}
    }
}

/** Passive FTP subset sufficient for desktop clients. No anonymous access or active-mode bounce. */
private class LocalFtp(private val root:File,private val address:String,private val password:String):Closeable{
    private val server=ServerSocket(0,20,InetAddress.getByName(address));val port:Int get()=server.localPort
    private val workers=Executors.newFixedThreadPool(4);private val clients=Collections.synchronizedList(ArrayList<Socket>())
    @Volatile private var running=true
    fun start(){Thread({while(running){try{val socket=server.accept();clients.add(socket);workers.submit{serve(socket)}}catch(_:Exception){if(running)close()}}},"oi-ftp-accept").apply{isDaemon=true;start()}}
    private fun serve(socket:Socket){var passive:ServerSocket?=null
        try{socket.soTimeout=120000;val reader=socket.getInputStream().bufferedReader();val writer=socket.getOutputStream().bufferedWriter();var user=false;var logged=false;var cwd="/";var rename:File?=null;var offset=0L
            fun reply(code:Int,text:String){writer.write("$code $text\r\n");writer.flush()}
            fun target(arg:String):File=within(root,if(arg.startsWith('/'))arg else cwd.trimEnd('/')+"/"+arg)
            fun relative(file:File)="/"+file.relativeTo(root).invariantSeparatorsPath
            fun data(block:(Socket)->Unit){val p=passive ?: throw IOException("Usa PASV o EPSV");reply(150,"Abriendo datos");p.soTimeout=30000;p.accept().use{peer->if(peer.inetAddress!=socket.inetAddress)throw IOException("Cliente de datos inesperado");peer.soTimeout=30000;block(peer)};p.close();passive=null;reply(226,"Transferencia completa")}
            reply(220,"OI Archivos FTP")
            while(running){val line=reader.readLine() ?: break;if(line.length>8192)break;val cmd=line.substringBefore(' ').uppercase();val arg=line.substringAfter(' ',"")
                if(cmd=="QUIT"){reply(221,"Hasta luego");break}
                if(cmd=="USER"){user=arg=="oi";reply(331,"Contraseña requerida");continue}
                if(cmd=="PASS"){logged=user&&MessageDigest.isEqual(arg.toByteArray(),password.toByteArray());reply(if(logged)230 else 530,if(logged)"Sesión iniciada"else"Credenciales rechazadas");continue}
                if(!logged){reply(530,"Inicia sesión");continue}
                try{when(cmd){
                    "SYST"->reply(215,"UNIX Type: L8")
                    "FEAT"->{writer.write("211-Features\r\n UTF8\r\n EPSV\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n211 End\r\n");writer.flush()}
                    "OPTS","TYPE","NOOP"->reply(200,"OK")
                    "PWD","XPWD"->reply(257,"\"${cwd.replace("\"","\"\"")}\"")
                    "CWD","CDUP"->{val raw=if(cmd=="CDUP")cwd.trimEnd('/').substringBeforeLast('/',"/").ifBlank{"/"}else arg;val f=target(raw);if(!f.isDirectory)throw IOException("No existe la carpeta");cwd=relative(f);reply(250,"Carpeta cambiada")}
                    "PASV","EPSV"->{passive?.close();passive=ServerSocket(0,1,InetAddress.getByName(address));val p=passive!!.localPort;if(cmd=="EPSV")reply(229,"Entering Extended Passive Mode (|||$p|)")else reply(227,"Entering Passive Mode (${address.replace('.',',')},${p/256},${p%256})")}
                    "LIST","NLST","MLSD"->{val f=if(arg.isBlank()||arg.startsWith('-'))target("")else target(arg);val children=(f.listFiles() ?: throw IOException("No se puede listar")).filter{!Files.isSymbolicLink(it.toPath())};data{peer->peer.getOutputStream().bufferedWriter().use{out->children.forEach{child->val name=child.name.replace('\r','_').replace('\n','_');val text=when(cmd){"NLST"->name;"MLSD"->"type=${if(child.isDirectory)"dir"else"file"};size=${child.length()}; $name";else->"${if(child.isDirectory)"d"else"-"}rw-r--r-- 1 oi oi ${child.length()} ${SimpleDateFormat("MMM dd HH:mm",Locale.US).format(Date(child.lastModified()))} $name"};out.write(text+"\r\n")}}}}
                    "RETR"->{val f=target(arg);if(!f.isFile)throw IOException("No existe el archivo");data{peer->f.inputStream().use{input->var skipped=0L;while(skipped<offset){val n=input.skip(offset-skipped);if(n<=0)throw IOException("Posición fuera del archivo");skipped+=n};input.copyTo(peer.getOutputStream())}};offset=0}
                    "STOR"->{val f=target(arg);if(offset!=0L)throw IOException("Reanudar subidas no es compatible");if(f.exists())throw IOException("El archivo ya existe; usa otro nombre");data{peer->SafeFiles.writeAtomic(f){part->peer.getInputStream().use{input->part.outputStream().use{input.copyTo(it)}}}}}
                    "SIZE"->reply(213,"${target(arg).length()}")
                    "MDTM"->reply(213,SimpleDateFormat("yyyyMMddHHmmss",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date(target(arg).lastModified())))
                    "REST"->{offset=arg.toLongOrNull()?.takeIf{it>=0} ?: throw IOException("Posición no válida");reply(350,"Posición aceptada")}
                    "MKD"->{val f=target(arg);if(!f.mkdir())throw IOException("No se pudo crear");reply(257,"\"${relative(f)}\"")}
                    "DELE"->{val f=target(arg);if(!f.isFile||!f.delete())throw IOException("No se pudo eliminar");reply(250,"Eliminado")}
                    "RMD"->{val f=target(arg);if(f==root||!f.isDirectory||!f.delete())throw IOException("Carpeta no vacía o protegida");reply(250,"Eliminada")}
                    "RNFR"->{rename=target(arg).takeIf{it.exists()&&it!=root} ?: throw IOException("No existe");reply(350,"Destino requerido")}
                    "RNTO"->{val src=rename ?: throw IOException("Usa RNFR");val dst=target(arg);if(dst.exists()||!src.renameTo(dst))throw IOException("No se pudo renombrar");rename=null;reply(250,"Renombrado")}
                    else->reply(502,"Comando no compatible")
                }}catch(_:Exception){passive?.close();passive=null;reply(550,"Operación rechazada o incompleta")}
            }
        }catch(_:Exception){}finally{runCatching{passive?.close()};clients.remove(socket);socket.close()}
    }
    override fun close(){running=false;runCatching{server.close()};synchronized(clients){clients.toList().forEach{runCatching{it.close()}};clients.clear()};workers.shutdownNow()}
}
