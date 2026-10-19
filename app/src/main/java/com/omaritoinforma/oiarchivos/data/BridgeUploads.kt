package com.omaritoinforma.oiarchivos.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Bounded uploads are staged privately and verified before publishing a new destination. */
class BridgeUploads(private val context: Context, private val config: BridgeConfig) {
    private val dir = File(context.cacheDir,"bridge-uploads").apply { mkdirs() }
    fun execute(action: String,args: JSONObject): JSONObject {
        if (!config.allowChanges) throw IOException("Los cambios están desactivados en el móvil")
        dir.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>3600000 }?.forEach { it.delete() }
        if (action == "begin_upload") {
            if ((dir.listFiles()?.count { it.extension == "json" } ?: 0) >= 4) throw IOException("Hay demasiadas subidas pendientes")
            val size=args.getLong("totalBytes")
            val hash=args.getString("sha256")
            if (size !in 0..268435456L || !Regex("[a-fA-F0-9]{64}").matches(hash)) throw IOException("Tamaño o SHA-256 no válido")
            if (size+10485760 > dir.usableSpace) throw IOException("No hay espacio para preparar el archivo")
            if (args.has("connectionId") && !config.allowRemote) throw IOException("Conexiones remotas desactivadas")
            val id=UUID.randomUUID().toString()
            File(dir,"$id.json").writeText(args.toString())
            File(dir,"$id.part").createNewFile()
            return JSONObject().put("uploadId",id).put("offset",0).put("maxChunkBytes",65536)
        }
        val id=args.getString("uploadId")
        if (!Regex("[a-f0-9-]{36}").matches(id)) throw IOException("Subida no válida")
        val meta=File(dir,"$id.json");val part=File(dir,"$id.part")
        if (!meta.isFile || !part.isFile) throw IOException("Subida caducada o inexistente")
        val target=JSONObject(meta.readText())
        if (action == "cancel_upload") { meta.delete();part.delete();return JSONObject().put("cancelled",true) }
        if (action == "upload_chunk") {
            val offset=args.getLong("offset");val bytes=Base64.decode(args.getString("base64"),Base64.NO_WRAP)
            if (offset<0 || bytes.size !in 1..65536 || offset+bytes.size>target.getLong("totalBytes")) throw IOException("Bloque no válido")
            RandomAccessFile(part,"rw").use {
                if (offset==it.length()) {it.seek(offset);it.write(bytes)}
                else if (offset+bytes.size<=it.length()) {
                    it.seek(offset);val previous=ByteArray(bytes.size);it.readFully(previous)
                    if (!previous.contentEquals(bytes)) throw IOException("El bloque repetido es diferente")
                } else throw IOException("Envía los bloques en orden")
            }
            meta.setLastModified(System.currentTimeMillis())
            return JSONObject().put("uploadId",id).put("offset",part.length())
        }
        if (action != "commit_upload") throw IOException("Acción de subida desconocida")
        if (part.length()!=target.getLong("totalBytes")) throw IOException("Faltan bloques")
        val digest=MessageDigest.getInstance("SHA-256")
        part.inputStream().use { stream -> val buf=ByteArray(65536);var n=stream.read(buf)
            while(n>=0) {if(n>0)digest.update(buf,0,n);n=stream.read(buf)} }
        val hash=digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(target.getString("sha256"),true)) throw IOException("El SHA-256 no coincide; el destino no se ha escrito")
        if (target.has("connectionId")) {
            RemoteFileAgent(context,config).execute("_commit_upload",target.put("uploadTemp",part.absolutePath))
        } else {
            val file=FileAgent(context,true,config.allowTrash).resolveUploadTarget(target.getString("path"))
            var created=false
            try {
                Files.newOutputStream(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { output ->
                    created=true;part.inputStream().use { it.copyTo(output) }
                }
            } catch(e:Exception) {if(created)file.delete();throw e}
        }
        meta.delete();part.delete()
        return JSONObject().put("created",target.getString("path")).put("bytes",target.getLong("totalBytes")).put("sha256",hash)
    }
}
