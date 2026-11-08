package plus.core.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small secrets such as API keys. */
interface SecretStore {
    fun get(id: String): String?
    fun put(id: String, value: String)
    fun remove(id: String)
}

/**
 * Keeps secrets encrypted with an AES key that never leaves the Android Keystore.
 * The encrypted value sits in a private preference, so Mihon's backups skip it; and even if
 * a system backup copies it to another phone, it cannot be decrypted there (the key stays behind).
 */
class KeystoreSecretStore(private val preferenceStore: PreferenceStore) : SecretStore {

    override fun get(id: String): String? {
        val stored = preference(id).get().ifEmpty { return null }
        return runCatching {
            val (iv, data) = stored.split(':', limit = 2).map { Base64.getDecoder().decode(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun put(id: String, value: String) {
        if (value.isEmpty()) {
            remove(id)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        preference(id).set(encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(data))
    }

    override fun remove(id: String) = preference(id).delete()

    private fun preference(id: String) =
        preferenceStore.getString(Preference.privateKey("plus_secret_$id"), "")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "relad_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
