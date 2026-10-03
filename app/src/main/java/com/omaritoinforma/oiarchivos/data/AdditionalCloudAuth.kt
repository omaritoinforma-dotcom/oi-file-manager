package com.omaritoinforma.oiarchivos.data

import android.content.Context
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Provider-supported device authorization and SugarSync app authorization. */
object AdditionalCloudAuth {
    data class DeviceApproval(
        val code: String,
        val userCode: String,
        val url: String,
        val expiresAt: Long,
        val intervalMs: Long
    )

    private val http = Http("")

    private fun baidu(method: String, params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = http.open("https://openapi.baidu.com/oauth/2.0/$method?$query", "GET")
        return http.response(connection).use {
            JSONObject(String(CloudXml.bounded(it), Charsets.UTF_8))
        }
    }

    fun baiduStart(c: Connection): DeviceApproval {
        if (c.clientId.isBlank() || c.clientSecret.isBlank())
            throw IOException("Configura las claves de tu aplicación Baidu")
        val result =
            baidu(
                "device/code",
                mapOf(
                    "response_type" to "device_code",
                    "client_id" to c.clientId,
                    "scope" to "basic,netdisk"))
        if (result.has("error")) throw IOException("Baidu no pudo iniciar la autorización")
        val url = java.net.URL(result.getString("verification_url"))
        if (url.protocol != "https" ||
            !(url.host == "baidu.com" || url.host.endsWith(".baidu.com")))
            throw IOException("Dirección de autorización no válida")
        return DeviceApproval(
            result.getString("device_code"),
            result.getString("user_code"),
            url.toString(),
            System.currentTimeMillis() + result.getLong("expires_in") * 1000,
            result.optLong("interval", 5).coerceIn(1, 60) * 1000)
    }

    suspend fun baiduFinish(ctx: Context, c: Connection, approval: DeviceApproval): Connection {
        var interval = approval.intervalMs
        while (System.currentTimeMillis() < approval.expiresAt) {
            currentCoroutineContext().ensureActive()
            val response =
                baidu(
                    "token",
                    mapOf(
                        "grant_type" to "device_token",
                        "code" to approval.code,
                        "client_id" to c.clientId,
                        "client_secret" to c.clientSecret))
            when (response.optString("error")) {
                "" -> return saveBaidu(ctx, c, response)
                "authorization_pending" -> {}
                "slow_down" -> interval = (interval + 5000).coerceAtMost(60000)
                else -> throw IOException("Baidu rechazó la autorización. Vuelve a entrar.")
            }
            delay(interval)
        }
        throw IOException("El código de Baidu caducó. Vuelve a entrar.")
    }

    private fun saveBaidu(ctx: Context, c: Connection, response: JSONObject): Connection {
        val previous = runCatching { JSONObject(c.authState).optString("refresh") }.getOrDefault("")
        val refresh = response.optString("refresh_token", previous)
        val token = response.optString("access_token")
        if (token.isBlank() || refresh.isBlank())
            throw IOException("Baidu no devolvió el acceso a los archivos")
        return save(
            ctx,
            c.copy(
                secret = token,
                authState = JSONObject().put("type", "baidu").put("refresh", refresh).toString(),
                expiresAt = System.currentTimeMillis() + response.getLong("expires_in") * 1000))
    }

    fun sugarLogin(ctx: Context, c: Connection, password: String): Connection {
        if (c.user.isBlank() ||
            password.isBlank() ||
            c.host.isBlank() ||
            c.clientId.isBlank() ||
            c.clientSecret.isBlank())
            throw IOException("Completa la cuenta y las claves de tu aplicación SugarSync")
        val body =
            CloudXml.body(
                "appAuthorization",
                mapOf(
                    "username" to c.user,
                    "password" to password,
                    "application" to c.host,
                    "accessKeyId" to c.clientId,
                    "privateAccessKey" to c.clientSecret))
        val refresh = sugarPost("app-authorization", body).first
        return sugarRefresh(ctx, c, refresh)
    }

    private fun sugarPost(path: String, body: String): Pair<String, org.w3c.dom.Document?> {
        val data = body.toByteArray(Charsets.UTF_8)
        val connection =
            http.open(
                "https://api.sugarsync.com/$path",
                "POST",
                mapOf(
                    "Content-Type" to "application/xml; charset=UTF-8",
                    "User-Agent" to "OIArchivos"),
                data.size.toLong())
        try {
            connection.outputStream.use { it.write(data) }
            val bytes = http.response(connection).use { CloudXml.bounded(it) }
            val location =
                sugarEndpoint(
                    connection.getHeaderField("Location")
                        ?: throw IOException("SugarSync no devolvió la autorización"))
            return location to if (bytes.isEmpty()) null else CloudXml.parse(bytes.inputStream())
        } finally {
            connection.disconnect()
        }
    }

    private fun sugarRefresh(ctx: Context, c: Connection, refresh: String): Connection {
        val result =
            sugarPost(
                "authorization",
                CloudXml.body(
                    "tokenAuthRequest",
                    mapOf(
                        "accessKeyId" to c.clientId,
                        "privateAccessKey" to c.clientSecret,
                        "refreshToken" to sugarEndpoint(refresh))))
        val document =
            result.second?.documentElement ?: throw IOException("SugarSync no devolvió la cuenta")
        val expires =
            runCatching {
                    OffsetDateTime.parse(CloudXml.text(document, "expiration"))
                        .toInstant()
                        .toEpochMilli()
                }
                .getOrElse { throw IOException("Caducidad de SugarSync no válida") }
        val user = sugarEndpoint(CloudXml.text(document, "user"))
        return save(
            ctx,
            c.copy(
                secret = result.first,
                expiresAt = expires,
                authState =
                    JSONObject()
                        .put("type", "sugarsync")
                        .put("refresh", refresh)
                        .put("user", user)
                        .toString()))
    }

    fun fresh(ctx: Context, c: Connection): Connection {
        val refresh = JSONObject(c.authState).getString("refresh")
        return when (c.protocol) {
            Protocol.BAIDU -> {
                val response =
                    baidu(
                        "token",
                        mapOf(
                            "grant_type" to "refresh_token",
                            "refresh_token" to refresh,
                            "client_id" to c.clientId,
                            "client_secret" to c.clientSecret))
                if (response.has("error")) throw IOException("Vuelve a entrar en Baidu")
                saveBaidu(ctx, c, response)
            }
            Protocol.SUGARSYNC -> sugarRefresh(ctx, c, refresh)
            else -> throw IOException("Proveedor de autorización no válido")
        }
    }

    @Synchronized
    private fun save(ctx: Context, connection: Connection): Connection {
        val store = ConnectionStore(ctx)
        store.save(store.load().filter { it.id != connection.id } + connection)
        return connection
    }
}
