package com.omaritoinforma.oiarchivos.data

import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Contraseña de la app, como «Configuración de Contraseña» de ES: una sola contraseña que protege
 * abrir la app, las conexiones de red y mostrar los archivos ocultos. Basta con escribirla una vez
 * por sesión, igual que en ES.
 *
 * Mejoras sobre ES: se guarda solo un hash PBKDF2-SHA256 con sal (ES la guarda cifrada de forma
 * reversible) y la sesión caduca si la app pasa más de [RELOCK_MS] en segundo plano.
 */
object AppLock {
    private const val ITERATIONS = 120_000
    private const val PREFIX = "pbkdf2-sha256"
    const val RELOCK_MS = 5 * 60 * 1000L

    /** Verdadero tras escribir bien la contraseña, hasta salir o pasar un rato en segundo plano. */
    @Volatile var verified = false
        private set

    private var leftAt = 0L

    fun encode(password: String, iterations: Int = ITERATIONS): String {
        require(password.isNotEmpty()) { tr("La contraseña no puede estar vacía") }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, iterations)
        val b64 = Base64.getEncoder()
        return listOf(PREFIX, iterations, b64.encodeToString(salt), b64.encodeToString(hash))
            .joinToString("$")
    }

    fun matches(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        return runCatching {
                val salt = Base64.getDecoder().decode(parts[2])
                val expected = Base64.getDecoder().decode(parts[3])
                MessageDigest.isEqual(derive(password, salt, iterations), expected)
            }
            .getOrDefault(false)
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Comprueba [password] con la guardada en [prefs]; si es correcta, abre la sesión. */
    fun unlock(prefs: Prefs, password: String): Boolean {
        val stored = prefs.lockHash
        val ok = stored.isNotEmpty() && matches(password, stored)
        if (ok) verified = true
        return ok
    }

    fun lock() {
        verified = false
    }

    private fun active(prefs: Prefs, enabled: Boolean) =
        enabled && prefs.lockHash.isNotEmpty() && !verified

    fun needsStart(prefs: Prefs) = active(prefs, prefs.lockStart)

    fun needsNetwork(prefs: Prefs) = active(prefs, prefs.lockNetwork)

    fun needsHidden(prefs: Prefs) = active(prefs, prefs.lockHidden)

    fun anyEnabled(prefs: Prefs) = prefs.lockStart || prefs.lockNetwork || prefs.lockHidden

    fun onBackground() {
        leftAt = SystemClock.elapsedRealtime()
    }

    /** Al volver a la app: si estuvo demasiado tiempo fuera, hay que escribir otra vez la contraseña. */
    fun onForeground() {
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > RELOCK_MS) verified = false
        leftAt = 0L
    }
}
