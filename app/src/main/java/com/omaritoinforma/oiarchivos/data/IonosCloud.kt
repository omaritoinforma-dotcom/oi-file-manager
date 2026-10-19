package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class IonosItem(val path: String, val name: String, val directory: Boolean, val size: Long, val modified: Long)
data class IonosListing(val items: List<IonosItem>, val stale: Boolean)

/** Permanent IONOS mount; tokens remain bound to the paired bridge HTTPS origin. */
class IonosCloud(private val ctx: Context) {
    private val paired = BridgeConfig(ctx)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = ctx.getSharedPreferences("oi_ionos_listing_cache", Context.MODE_PRIVATE)

    private fun endpoint(): HttpUrl {
        check(paired.valid()) { "Empareja primero OI Archivos con tu servidor" }
        val bridge = paired.endpoint.toHttpUrl()
        require(bridge.isHttps && bridge.username.isEmpty() && bridge.password.isEmpty())
        require(bridge.encodedPath == "/oi-android-bridge/index.php") {
            "Este montaje necesita el puente PHP de IONOS"
        }
        return bridge.newBuilder().encodedPath("/oi-cloud/index.php").query(null).fragment(null).build()
    }
    private fun request(url: HttpUrl): Request.Builder {
        val (device, site) = paired.credentials()
        return Request.Builder().url(url).header("Authorization", "Bearer $device")
            .apply { if (site != null) header("OAI-Sites-Authorization", "Bearer $site") }
    }
    private fun json(response: okhttp3.Response): JSONObject = response.use {
        if (!it.isSuccessful) throw IOException("IONOS respondió HTTP " + it.code)
        JSONObject(it.body?.string().orEmpty())
    }
    private fun key(path: String): String = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun fromJson(s: String, stale: Boolean): IonosListing {
        val a = JSONObject(s).getJSONArray("items")
        val list = (0 until a.length()).map { i ->
            val j=a.getJSONObject(i)
            IonosItem(j.getString("path"), j.getString("name"), j.getBoolean("directory"), j.getLong("size"), j.optLong("modified"))
        }
        return IonosListing(list,stale)
    }
    fun cached(path: String): IonosListing? =
        prefs.getString(key(path),null)?.let { runCatching { fromJson(it,true) }.getOrNull() }

    fun list(path: String): IonosListing {
        val uri=endpoint().newBuilder().addQueryParameter("action","list").addQueryParameter("path",path).build()
        val text=client.newCall(request(uri).get().build()).execute().use {
            if (!it.isSuccessful) throw IOException("IONOS HTTP " + it.code)
            it.body?.string() ?: throw IOException("Respuesta vacía")
        }
        val result=fromJson(text,false)
        prefs.edit().putString(key(path),text).apply()
        return result
    }
    fun isUnmetered(): Boolean {
        val cm=ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    private fun post(action: String, args: JSONObject) {
        check(paired.allowChanges) { "Activa Permitir cambios de archivos en Ajustes" }
        if (action == "delete") check(paired.allowDelete) { "El borrado definitivo necesita autorización" }
        val u=endpoint().newBuilder().addQueryParameter("action",action).build()
        val body=args.toString().toRequestBody("application/json".toMediaType())
        json(client.newCall(request(u).post(body).build()).execute())
    }
    fun mkdir(path: String) = post("mkdir",JSONObject().put("path",path))
    fun rename(path: String, dest: String) = post("rename",JSONObject().put("path",path).put("destination",dest))
    fun delete(path: String) = post("delete",JSONObject().put("path",path))

    /** Explicitly cached files remain available offline. */
    fun offlineCopy(remotePath: String): File {
        val base=File(ctx.getExternalFilesDir(null), "ionos-offline").apply { mkdirs() }
        val ext=remotePath.substringAfterLast('/').substringAfterLast('.',"").take(12).replace(Regex("[^a-zA-Z0-9]"),"")
        return File(base,key(remotePath)+(if(ext.isNotEmpty())".$ext" else ""))
    }
    fun download(item: IonosItem): File {
        require(!item.directory)
        check(isUnmetered()) { "Descarga bloqueada en datos móviles; conecta a Wi-Fi" }
        val dest=offlineCopy(item.path)
        val tmp=File(dest.parentFile,"."+dest.name+".part")
        val u=endpoint().newBuilder().addQueryParameter("action","download").addQueryParameter("path",item.path).build()
        try {
            client.newCall(request(u).get().build()).execute().use { r ->
                if(!r.isSuccessful)throw IOException("Descarga HTTP " + r.code)
                val body=r.body ?: throw IOException("Descarga vacía")
                var count=0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf=ByteArray(65536)
                        while (true) {
                            val n=input.read(buf);if(n<0)break
                            count+=n; if(count>512L*1024*1024)throw IOException("Límite de descarga 512 MiB")
                            out.write(buf,0,n)
                        }
                    }
                }
                if(item.size>=0 && count!=item.size)throw IOException("Descarga incompleta")
            }
            if(dest.exists()&&!dest.delete())throw IOException("No se pudo reemplazar caché")
            if(!tmp.renameTo(dest))throw IOException("No se pudo guardar la descarga")
            return dest
        }finally{tmp.delete()}
    }
    fun upload(folder: String, uri: Uri) {
        check(paired.allowChanges) { "Activa Permitir cambios de archivos en Ajustes" }
        check(isUnmetered()) { "Subida bloqueada en datos móviles; conecta a Wi-Fi" }
        val resolver=ctx.contentResolver
        var name="archivo"
        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst())name=cursor.getString(0) ?: name
        }
        val streamBody=object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer=ByteArray(65536);var total=0L
                    while(true){
                        val n=input.read(buffer);if(n<0)break
                        total+=n;if(total>33554432L)throw IOException("Máximo 32 MiB")
                        sink.write(buffer,0,n)
                    }
                }?:throw IOException("Archivo no accesible")
            }
        }
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("path",folder)
            .addFormDataPart("file",name,streamBody).build()
        val u=endpoint().newBuilder().addQueryParameter("action","upload").build()
        json(client.newCall(request(u).post(body).build()).execute())
    }
}
