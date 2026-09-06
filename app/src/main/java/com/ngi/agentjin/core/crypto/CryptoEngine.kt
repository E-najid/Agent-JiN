package com.ngi.agentjin.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * Password-based encryption. Security comes only from the user password;
 * salts are public and stored in manifest.json. The password is never persisted.
 */
class CryptoEngine(
    private val random: SecureRandom = SecureRandom(),
) {
    data class KdfParams(
        val memoryKiB: Int = DEFAULT_MEMORY_KIB,
        val iterations: Int = DEFAULT_ITERATIONS,
        val parallelism: Int = DEFAULT_PARALLELISM,
    )

    data class DerivedMaterial(
        val encSalt: ByteArray,
        val verifySalt: ByteArray,
        val verifyHash: ByteArray,
        val encKey: ByteArray,
        val kdf: KdfParams,
    )

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LEN)
        random.nextBytes(salt)
        return salt
    }

    fun deriveOnSetup(password: CharArray, kdf: KdfParams = KdfParams()): DerivedMaterial {
        val encSalt = generateSalt()
        val verifySalt = generateSalt()
        val encKey = argon2id(password, encSalt, KEY_LEN, kdf)
        val verifyHash = argon2id(password, verifySalt, KEY_LEN, kdf)
        return DerivedMaterial(encSalt, verifySalt, verifyHash, encKey, kdf)
    }

    fun deriveEncryptionKey(password: CharArray, encSalt: ByteArray, kdf: KdfParams): ByteArray {
        return argon2id(password, encSalt, KEY_LEN, kdf)
    }

    fun verifyPassword(
        password: CharArray,
        verifySalt: ByteArray,
        expectedHash: ByteArray,
        kdf: KdfParams,
    ): Boolean {
        val got = argon2id(password, verifySalt, expectedHash.size, kdf)
        return constantTimeEquals(got, expectedHash).also {
            got.fill(0)
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LEN)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        val out = ByteArray(MAGIC.size + iv.size + ct.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        System.arraycopy(iv, 0, out, MAGIC.size, iv.size)
        System.arraycopy(ct, 0, out, MAGIC.size + iv.size, ct.size)
        return out
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size < MAGIC.size + GCM_IV_LEN + 16) {
            throw CryptoException("ciphertext too short")
        }
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) {
                throw CryptoException("not an Agent JiN encrypted blob")
            }
        }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + GCM_IV_LEN)
        val ct = blob.copyOfRange(MAGIC.size + GCM_IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw CryptoException("decrypt failed", e)
        }
    }

    fun wipe(bytes: ByteArray) {
        bytes.fill(0)
    }

    private fun argon2id(password: CharArray, salt: ByteArray, outLen: Int, kdf: KdfParams): ByteArray {
        val gen = Argon2BytesGenerator()
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(kdf.iterations)
            .withMemoryAsKB(kdf.memoryKiB)
            .withParallelism(kdf.parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .build()
        gen.init(params)
        val out = ByteArray(outLen)
        val passBytes = password.toByteArrayUtf8()
        try {
            gen.generateBytes(passBytes, out)
        } finally {
            passBytes.fill(0)
        }
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var acc: Byte = 0
        for (i in a.indices) acc = acc xor a[i] xor b[i]
        return acc.toInt() == 0
    }

    companion object {
        val MAGIC = byteArrayOf('A'.code.toByte(), 'J'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())
        const val SALT_LEN = 16
        const val KEY_LEN = 32
        const val GCM_IV_LEN = 12
        const val GCM_TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        // 16 MiB Argon2id — strong enough for an on-device PIN, small enough
        // that a 3GB phone will not be LMK-killed while deriving.
        const val DEFAULT_MEMORY_KIB = 16 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1
    }
}

class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun CharArray.toByteArrayUtf8(): ByteArray {
    val s = String(this)
    return s.toByteArray(Charsets.UTF_8)
}

fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

fun ByteArray.toHex(): String = joinToString("") { b -> "%02x".format(b) }
