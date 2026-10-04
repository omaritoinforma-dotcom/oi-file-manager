package com.omaritoinforma.oiarchivos.data

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest as GoogleAuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.openid.appauth.*

/**
 * AppAuth owns PKCE/state verification and token expiry. OAuth state is stored inside the Keystore
 * vault.
 */
object CloudAuth {
    const val REDIRECT = "com.omaritoinforma.oiarchivos.oauth:/oauth2redirect"
    val providers = setOf(Protocol.DROPBOX, Protocol.ONEDRIVE, Protocol.BOX, Protocol.YANDEX)

    private data class Provider(val authorize: String, val token: String, val scopes: String)

    private fun provider(protocol: Protocol) =
        when (protocol) {
            Protocol.DROPBOX ->
                Provider(
                    "https://www.dropbox.com/oauth2/authorize",
                    "https://api.dropboxapi.com/oauth2/token",
                    "files.metadata.read files.metadata.write files.content.read files.content.write")
            Protocol.ONEDRIVE ->
                Provider(
                    "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                    "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                    "offline_access https://graph.microsoft.com/Files.ReadWrite.All")
            Protocol.BOX ->
                Provider(
                    "https://account.box.com/api/oauth2/authorize",
                    "https://api.box.com/oauth2/token",
                    "root_readwrite")
            Protocol.YANDEX ->
                Provider(
                    "https://oauth.yandex.com/authorize",
                    "https://oauth.yandex.com/token",
                    "cloud_api:disk.read cloud_api:disk.write")
            else -> throw IOException(tr("Este proveedor no usa este inicio de sesión"))
        }

    fun request(service: AuthorizationService, c: Connection): Intent {
        if (c.clientId.isBlank())
            throw IOException(
                tr("Falta registrar el identificador OAuth de OI Archivos para {0}", c.protocol.label))
        val p = provider(c.protocol)
        val request =
            AuthorizationRequest.Builder(
                    AuthorizationServiceConfiguration(Uri.parse(p.authorize), Uri.parse(p.token)),
                    c.clientId,
                    ResponseTypeValues.CODE,
                    Uri.parse(REDIRECT))
                .setScope(p.scopes)
                .apply {
                    if (c.protocol == Protocol.DROPBOX)
                        setAdditionalParameters(mapOf("token_access_type" to "offline"))
                }
                .build()
        return service.getAuthorizationRequestIntent(request)
    }

    private fun client(c: Connection): ClientAuthentication =
        if (c.clientSecret.isBlank()) NoClientAuthentication.INSTANCE
        else ClientSecretPost(c.clientSecret)

    fun finish(
        ctx: Context,
        service: AuthorizationService,
        c: Connection,
        intent: Intent?,
        completed: (Result<Connection>) -> Unit
    ) {
        if (intent == null) {
            completed(Result.failure(IOException(tr("Inicio de sesión cancelado"))))
            return
        }
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        if (response == null) {
            completed(
                Result.failure(
                    IOException(error?.errorDescription ?: tr("Inicio de sesión cancelado"))))
            return
        }
        if (response.request.clientId != c.clientId ||
            response.request.redirectUri.toString() != REDIRECT) {
            completed(Result.failure(IOException(tr("Respuesta OAuth no válida"))))
            return
        }
        val state = AuthState(response, error)
        service.performTokenRequest(response.createTokenExchangeRequest(), client(c)) {
            token,
            failure ->
            state.update(token, failure)
            if (token == null || token.accessToken.isNullOrBlank())
                completed(Result.failure(IOException(tr("No se pudo autorizar la cuenta"))))
            else
                completed(
                    runCatching {
                        save(
                            ctx,
                            c.copy(
                                secret = token.accessToken!!,
                                authState = state.jsonSerializeString(),
                                expiresAt = token.accessTokenExpirationTime ?: 0))
                    })
        }
    }

    fun googleRequest(c: Connection? = null): GoogleAuthorizationRequest =
        GoogleAuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive")))
            .apply {
                c?.googleAccount
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setAccount(Account(it, "com.google")) }
            }
            .build()

    fun googleFinish(ctx: Context, result: AuthorizationResult): Connection {
        val token =
            result.accessToken ?: throw IOException(tr("Google no devolvió un permiso de acceso"))
        val email = result.toGoogleSignInAccount()?.email.orEmpty()
        val previous =
            ConnectionStore(ctx).load().firstOrNull {
                it.protocol == Protocol.DRIVE &&
                    it.googleAccount == email &&
                    it.authState == "google-sdk"
            }
        return save(
            ctx,
            (previous
                    ?: Connection(
                        label = email.ifBlank { "Google Drive" },
                        protocol = Protocol.DRIVE,
                        host = "",
                        port = 443,
                        user = "",
                        secret = "",
                        root = "root"))
                .copy(
                    secret = token,
                    googleAccount = email,
                    authState = "google-sdk",
                    expiresAt = System.currentTimeMillis() + 45 * 60 * 1000))
    }

    @Synchronized
    private fun save(ctx: Context, c: Connection): Connection {
        val store = ConnectionStore(ctx)
        store.save(store.load().filter { it.id != c.id } + c)
        return c
    }

    /** Called on the IO dispatcher before opening a cloud connection. */
    @Synchronized
    fun fresh(ctx: Context, connection: Connection): Connection {
        if (connection.authState.isBlank()) return connection
        val c = ConnectionStore(ctx).load().firstOrNull { it.id == connection.id } ?: connection
        if (c.expiresAt > System.currentTimeMillis() + 60000) return c
        if (c.protocol == Protocol.BAIDU || c.protocol == Protocol.SUGARSYNC)
            return AdditionalCloudAuth.fresh(ctx, c)
        if (c.authState == "google-sdk") {
            val result =
                Tasks.await(
                    Identity.getAuthorizationClient(ctx).authorize(googleRequest(c)),
                    30,
                    TimeUnit.SECONDS)
            if (result.hasResolution())
                throw IOException(tr("Vuelve a autorizar Google Drive desde la pantalla de conexiones"))
            val token = result.accessToken ?: throw IOException(tr("Vuelve a entrar en Google Drive"))
            return save(
                ctx,
                c.copy(secret = token, expiresAt = System.currentTimeMillis() + 45 * 60 * 1000))
        }
        val state = AuthState.jsonDeserialize(c.authState)
        val service = AuthorizationService(ctx)
        val latch = CountDownLatch(1)
        var token: String? = null
        var failure: AuthorizationException? = null
        try {
            state.performActionWithFreshTokens(service, client(c)) { access, _, error ->
                token = access
                failure = error
                latch.countDown()
            }
            if (!latch.await(45, TimeUnit.SECONDS) || failure != null || token.isNullOrBlank())
                throw IOException(
                    tr("No se pudo renovar la cuenta. Vuelve a iniciar sesión en {0}", c.protocol.label))
            return save(
                ctx,
                c.copy(
                    secret = token!!,
                    authState = state.jsonSerializeString(),
                    expiresAt = state.accessTokenExpirationTime ?: 0))
        } finally {
            service.dispose()
        }
    }
}
