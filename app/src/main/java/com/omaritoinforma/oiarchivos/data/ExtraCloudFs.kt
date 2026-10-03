package com.omaritoinforma.oiarchivos.data

import org.json.JSONObject
import org.w3c.dom.Element
import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

internal class YandexFs(c:Connection):RemoteFs{
    private val api="https://cloud-api.yandex.net/v1/disk/resources"
    private val http=Http("OAuth ${c.secret}")
    private fun json(url:String,method:String="GET")=JSONObject(http.request(url,method))
    override fun list(path:String):List<RemoteEntry>{val out=ArrayList<RemoteEntry>();var offset=0
        while(true){val items=json("$api?path=${encode(path)}&limit=1000&offset=$offset").getJSONObject("_embedded").getJSONArray("items");for(i in 0 until items.length()){val f=items.getJSONObject(i);out+=RemoteEntry(f.getString("path"),f.getString("name"),f.getString("type")=="dir",f.optLong("size",-1))};if(items.length()<1000)break;offset+=items.length();if(offset>50000)throw IOException("La carpeta supera el límite de 50.000 elementos")};return out}
    private fun signed(url:String):String{val href=json(url).getString("href");if(URL(href).protocol!="https")throw IOException("Respuesta sin HTTPS");return href}
    override fun read(path:String):InputStream{val link=signed("$api/download?path=${encode(path)}");return Http("").response(Http("").open(link,"GET"))}
    override fun write(parent:String,name:String,input:InputStream,size:Long):String{val path=RemoteFiles.join(parent,name);val link=signed("$api/upload?path=${encode(path)}&overwrite=false");val c=Http("").open(link,"PUT",size=size);try{c.outputStream.use{input.copyTo(it)};Http("").response(c).close()}finally{c.disconnect()};return path}
    override fun mkdir(parent:String,name:String):String=RemoteFiles.join(parent,name).also{http.request("$api?path=${encode(it)}","PUT")}
    override fun rename(entry:RemoteEntry,name:String){http.request("$api/move?from=${encode(entry.path)}&path=${encode(RemoteFiles.join(entry.path.substringBeforeLast('/'),name))}&overwrite=false","POST")}
    override fun delete(entry:RemoteEntry){http.request("$api?path=${encode(entry.path)}&permanently=false","DELETE")}
}

internal class BoxFs(c:Connection):RemoteFs{
    private val api="https://api.box.com/2.0";private val http=Http("Bearer ${c.secret}")
    private fun json(url:String,method:String="GET",body:JSONObject?=null)=JSONObject(http.request(url,method,body?.toString(),mapOf("Content-Type" to "application/json")))
    override fun list(path:String):List<RemoteEntry>{val out=ArrayList<RemoteEntry>();var offset=0;do{val result=json("$api/folders/${encode(path.ifBlank{"0"})}/items?limit=1000&offset=$offset&fields=id,type,name,size");val entries=result.getJSONArray("entries");for(i in 0 until entries.length()){val f=entries.getJSONObject(i);out+=RemoteEntry(f.getString("type")+":"+f.getString("id"),f.getString("name"),f.getString("type")=="folder",f.optLong("size",-1))};offset+=entries.length();if(offset>=result.getInt("total_count"))break;if(offset>50000)throw IOException("La carpeta supera el límite de 50.000 elementos")}while(true);return out}
    private fun id(path:String)=path.substringAfter(':',path)
    override fun read(path:String):InputStream{val c=http.open("$api/files/${encode(id(path))}/content","GET");val code=c.responseCode;if(code in 300..399){val location=c.getHeaderField("Location") ?: throw IOException("No se recibió una descarga");c.disconnect();if(URL(location).protocol!="https")throw IOException("Descarga sin HTTPS");return Http("").response(Http("").open(location,"GET"))};return http.response(c)}
    override fun mkdir(parent:String,name:String):String{SafeFiles.requireName(name);return "folder:"+json("$api/folders","POST",JSONObject().put("name",name).put("parent",JSONObject().put("id",id(parent)))).getString("id")}
    override fun write(parent:String,name:String,input:InputStream,size:Long):String{
        SafeFiles.requireName(name)
        val boundary="OI"+UUID.randomUUID().toString().replace("-","")
        val attributes=JSONObject().put("name",name).put("parent",JSONObject().put("id",id(parent))).toString()
        val head="--$boundary\r\nContent-Disposition: form-data; name=\"attributes\"\r\n\r\n$attributes\r\n--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray(Charsets.UTF_8)
        val tail="\r\n--$boundary--\r\n".toByteArray()
        val c=http.open("https://upload.box.com/api/2.0/files/content","POST",mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),head.size+size+tail.size)
        try{c.outputStream.use{it.write(head);input.copyTo(it);it.write(tail)};return "file:"+JSONObject(http.response(c).bufferedReader().use{it.readText()}).getJSONArray("entries").getJSONObject(0).getString("id")}finally{c.disconnect()}
    }
    override fun rename(entry:RemoteEntry,name:String){SafeFiles.requireName(name);http.request("$api/${if(entry.directory)"folders"else"files"}/${encode(id(entry.path))}","PUT",JSONObject().put("name",name).toString(),mapOf("Content-Type" to "application/json"))}
    override fun delete(entry:RemoteEntry){http.request("$api/${if(entry.directory)"folders"else"files"}/${encode(id(entry.path))}?recursive=true","DELETE")}
}

/** S3-compatible HTTPS endpoints, path-style buckets, AWS Signature V4 and streamed payloads. */
internal class S3Fs(private val account:Connection):RemoteFs{
    private val base=account.host.trimEnd('/');private val region=account.fingerprint.ifBlank{"us-east-1"}
    init{if(URL(base).protocol!="https")throw IOException("S3 requiere un endpoint HTTPS");if(account.user.isBlank()||account.secret.isBlank())throw IOException("Faltan las claves de S3")}
    private fun uri(path:String)=base+"/"+path.trimStart('/').split('/').joinToString("/"){encode(it)}
    private fun hmac(key:ByteArray,text:String)=Mac.getInstance("HmacSHA256").apply{init(SecretKeySpec(key,"HmacSHA256"))}.doFinal(text.toByteArray())
    private fun hex(value:ByteArray)=value.joinToString(""){"%02x".format(it)}
    private fun request(path:String,method:String,query:Map<String,String> = emptyMap(),extra:Map<String,String> = emptyMap(),input:InputStream?=null,size:Long?=null):InputStream{
        val now=Date();val day=SimpleDateFormat("yyyyMMdd",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(now);val time=SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(now)
        val canonicalQuery=query.entries.map{encode(it.key) to encode(it.value)}.sortedWith(compareBy<Pair<String,String>>{it.first}.thenBy{it.second}).joinToString("&"){it.first+"="+it.second}
        val url=uri(path)+if(canonicalQuery.isEmpty())""else"?$canonicalQuery";val parsed=URL(url)
        val headers=sortedMapOf("host" to parsed.authority,"x-amz-content-sha256" to "UNSIGNED-PAYLOAD","x-amz-date" to time).apply{putAll(extra.mapKeys{it.key.lowercase()})}
        val signed=headers.keys.joinToString(";");val canonical=method+"\n"+parsed.path+"\n"+canonicalQuery+"\n"+headers.entries.joinToString(""){it.key+":"+it.value.trim()+"\n"}+"\n"+signed+"\nUNSIGNED-PAYLOAD"
        val scope="$day/$region/s3/aws4_request";val toSign="AWS4-HMAC-SHA256\n$time\n$scope\n"+hex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        val signing=hmac(hmac(hmac(hmac(("AWS4"+account.secret).toByteArray(),day),region),"s3"),"aws4_request")
        val auth="AWS4-HMAC-SHA256 Credential=${account.user}/$scope, SignedHeaders=$signed, Signature="+hex(hmac(signing,toSign));signing.fill(0)
        val c=Http(auth).open(url,method,headers,size)
        try{if(input!=null)c.outputStream.use{input.copyTo(it)}else if(size!=null)c.outputStream.close();return Http(auth).response(c)}catch(e:Exception){c.disconnect();throw e}
    }
    private fun xml(input:InputStream):org.w3c.dom.Document=input.use{DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);setFeature("http://xml.org/sax/features/external-general-entities",false);setFeature("http://xml.org/sax/features/external-parameter-entities",false)}.newDocumentBuilder().parse(it)}
    override fun list(path:String):List<RemoteEntry>{val clean=path.trim('/');val bucket=clean.substringBefore('/');if(bucket.isBlank())throw IOException("Escribe /nombre-del-bucket en la carpeta inicial");val prefix=if(clean.contains('/'))clean.substringAfter('/').trimEnd('/')+"/"else"";val out=ArrayList<RemoteEntry>();var next=""
        do{val query=mutableMapOf("list-type" to "2","delimiter" to "/","prefix" to prefix);if(next.isNotEmpty())query["continuation-token"]=next;val doc=xml(request("/$bucket","GET",query));val common=doc.getElementsByTagNameNS("*","CommonPrefixes");for(i in 0 until common.length){val key=(common.item(i)as Element).getElementsByTagNameNS("*","Prefix").item(0).textContent.trimEnd('/');out+=RemoteEntry("/$bucket/$key",key.substringAfterLast('/'),true,0)};val objects=doc.getElementsByTagNameNS("*","Contents");for(i in 0 until objects.length){val item=objects.item(i)as Element;val key=item.getElementsByTagNameNS("*","Key").item(0).textContent;if(key==prefix||key.endsWith('/'))continue;out+=RemoteEntry("/$bucket/$key",key.substringAfterLast('/'),false,item.getElementsByTagNameNS("*","Size").item(0).textContent.toLong())};next=doc.getElementsByTagNameNS("*","NextContinuationToken").item(0)?.textContent.orEmpty();if(out.size>50000)throw IOException("La carpeta supera el límite de 50.000 elementos")}while(next.isNotEmpty());return out}
    override fun read(path:String)=request(path,"GET")
    override fun write(parent:String,name:String,input:InputStream,size:Long):String=RemoteFiles.join(parent,name).also{request(it,"PUT",input=input,size=size).close()}
    override fun mkdir(parent:String,name:String):String=RemoteFiles.join(parent,name).also{request("$it/","PUT",size=0).close()}
    override fun rename(entry:RemoteEntry,name:String){if(entry.directory)throw IOException("Para cambiar una carpeta S3, copia sus archivos a una carpeta nueva");val target=RemoteFiles.join(entry.path.substringBeforeLast('/'),name);val result=xml(request(target,"PUT",extra=mapOf("x-amz-copy-source" to "/"+entry.path.trimStart('/').split('/').joinToString("/"){encode(it)},"if-none-match" to "*"),size=0));if(result.documentElement.localName!="CopyObjectResult")throw IOException("S3 no confirmó la copia");delete(entry)}
    override fun delete(entry:RemoteEntry){if(entry.directory){list(entry.path).forEach{delete(it)};request(entry.path.trimEnd('/')+"/","DELETE").close()}else request(entry.path,"DELETE").close()}
}
