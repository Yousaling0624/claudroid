package app.claudroid.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets (host tokens) at rest. JVM tests substitute a fake. */
interface SecretCipher {
    fun seal(plaintext: String): String
    fun open(sealed: String): String?
}

/**
 * Android Keystore AES-GCM. The key never leaves the keystore; a backup or a copied
 * hosts.json cannot be decrypted on another device.
 */
class AndroidSecretBox(
    private val alias: String = "claudroid_remote_hosts",
) : SecretCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        return "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = runCatching {
        if (!sealed.startsWith("v1:")) return null
        val packed = Base64.decode(sealed.removePrefix("v1:"), Base64.NO_WRAP)
        if (packed.size <= 12) return null
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()
}
