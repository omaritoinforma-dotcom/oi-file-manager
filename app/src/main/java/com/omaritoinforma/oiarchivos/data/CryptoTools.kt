package com.omaritoinforma.oiarchivos.data

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * V2: authenticated 64 KiB chunks + an authenticated end marker; bounded RAM even for large files.
 */
object CryptoTools {
    private val MAGIC = byteArrayOf(79, 73, 69, 78, 67, 2)
    private const val CHUNK = 65536
    private const val ITERATIONS = 210000

    suspend fun transform(
        source: File,
        target: File,
        password: CharArray,
        decrypt: Boolean,
        report: (OpProgress) -> Unit
    ) {
        if (password.isEmpty()) throw IOException("Escribe una contraseña")
        if (target.exists()) throw IOException("El destino ya existe")
        val temp = File.createTempFile(".oi-crypto-", ".tmp", target.parentFile)
        var key: ByteArray? = null
        try {
            source.inputStream().buffered().use { raw ->
                DataInputStream(raw).use { input ->
                    DataOutputStream(temp.outputStream().buffered()).use { output ->
                        val salt = ByteArray(16)
                        val prefix = ByteArray(8)
                        if (decrypt) {
                            val magic = ByteArray(MAGIC.size)
                            input.readFully(magic)
                            if (!magic.contentEquals(MAGIC))
                                throw IOException(
                                    "No es un archivo cifrado compatible de OI Archivos")
                            input.readFully(salt)
                            input.readFully(prefix)
                        } else {
                            SecureRandom().apply {
                                nextBytes(salt)
                                nextBytes(prefix)
                            }
                            output.write(MAGIC)
                            output.write(salt)
                            output.write(prefix)
                        }
                        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
                        key =
                            try {
                                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                    .generateSecret(spec)
                                    .encoded
                            } finally {
                                spec.clearPassword()
                            }
                        val header = MAGIC + salt + prefix
                        var index = 0
                        var done = 0L
                        fun cipher(length: Int): Cipher =
                            Cipher.getInstance("AES/GCM/NoPadding").apply {
                                init(
                                    if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
                                    SecretKeySpec(key, "AES"),
                                    GCMParameterSpec(
                                        128, prefix + ByteBuffer.allocate(4).putInt(index).array()))
                                updateAAD(
                                    header +
                                        ByteBuffer.allocate(8).putInt(index).putInt(length).array())
                            }
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (index == Int.MAX_VALUE)
                                throw IOException("Archivo demasiado grande")
                            val length: Int
                            if (decrypt) {
                                length = input.readInt()
                                if (length !in 0..CHUNK) throw IOException("Archivo cifrado dañado")
                                val block = ByteArray(length + 16)
                                input.readFully(block)
                                val plaintext = cipher(length).doFinal(block)
                                if (plaintext.size != length)
                                    throw IOException("Longitud no válida")
                                if (length == 0) {
                                    if (input.read() != -1)
                                        throw IOException("Datos adicionales después del final")
                                    break
                                }
                                output.write(plaintext)
                                plaintext.fill(0)
                                done += length + 20
                            } else {
                                val buffer = ByteArray(CHUNK)
                                var n = 0
                                while (n < CHUNK) {
                                    val got = input.read(buffer, n, CHUNK - n)
                                    if (got < 0) break
                                    n += got
                                }
                                length = n
                                output.writeInt(length)
                                output.write(cipher(length).doFinal(buffer, 0, length))
                                buffer.fill(0)
                                if (length == 0) break
                                done += length
                            }
                            index++
                            report(
                                OpProgress(
                                    if (decrypt) "Descifrando" else "Cifrando",
                                    source.name,
                                    done,
                                    source.length()))
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            SafeFiles.commit(temp, target, false)
        } finally {
            temp.delete()
            key?.fill(0)
            password.fill('\u0000')
        }
    }
}
