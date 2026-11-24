package info.jevqq.analyst.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Field-level string encryption for the local history database. JVM tests inject a fake. */
internal interface FieldCipher {
    fun encrypt(plain: String): ByteArray
    fun decrypt(blob: ByteArray): String
}

/** AES-256/GCM with an Android Keystore key; IV is prepended to the ciphertext blob. */
internal class AesGcmFieldCipher(context: Context) : FieldCipher {
    private val keyAlias = "${context.packageName}.history_key"

    override fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    override fun decrypt(blob: ByteArray): String {
        require(blob.size > GCM_IV_BYTES) { "加密数据损坏" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        return String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    @Synchronized private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
