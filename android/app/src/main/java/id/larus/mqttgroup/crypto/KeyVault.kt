package id.larus.mqttgroup.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Every long-lived secret on this phone, and the one hardware-backed key that protects them.
 *
 * The Keystore key never leaves secure hardware, so it cannot be copied off the device or out
 * of a backup. Everything else — the database passphrase, the blob key, and each group key —
 * is a random 256-bit key sealed under it. The sealed bytes are then safe to keep in ordinary
 * storage: plain SharedPreferences for the two bootstrap keys (they must be readable before
 * the encrypted database can be opened), and inside the encrypted database for group keys,
 * which get both layers.
 *
 * Deliberately not `setUserAuthenticationRequired`: a background location service has to
 * encrypt a fix while the screen is locked. Requiring auth would mean the app stops working
 * the moment it matters most.
 */
class KeyVault(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The SQLCipher passphrase. Created on first call and stable for the life of the install. */
    fun databaseKey(): ByteArray = bootstrapKey(KEY_DATABASE)

    /** Encrypts attachment blobs on disk. Separate from the database key so either can rotate. */
    fun blobKey(): ByteArray = bootstrapKey(KEY_BLOB)

    /**
     * Seals a group key for storage, as `nonce || ciphertext || tag`. The result is safe to
     * write anywhere this app can write: without the Keystore key, which never leaves secure
     * hardware, the bytes are noise.
     */
    fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        // The Keystore generates its own IV and refuses one we supply, so read it back.
        return cipher.iv + cipher.doFinal(secret)
    }

    fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > Aead.NONCE_BYTES + Aead.TAG_BYTES) { "wrapped secret is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            masterKey(),
            GCMParameterSpec(Aead.TAG_BYTES * 8, wrapped, 0, Aead.NONCE_BYTES),
        )
        return cipher.doFinal(wrapped, Aead.NONCE_BYTES, wrapped.size - Aead.NONCE_BYTES)
    }

    /**
     * Forgets every secret. Anything still encrypted with them — the database file, the blob
     * directory — becomes permanently unreadable, which is the point: this is what "delete all
     * data" leans on, so a forensic recovery of the deleted files yields nothing.
     */
    fun destroy() = synchronized(this) {
        prefs.edit().clear().commit()
        cachedMaster = null
        keyStore().deleteEntry(MASTER_ALIAS)
    }

    private fun bootstrapKey(name: String): ByteArray = synchronized(this) {
        prefs.getString(name, null)?.let { return unwrap(Base64.decode(it, Base64.NO_WRAP)) }
        val fresh = Aead.randomKey()
        prefs.edit()
            .putString(name, Base64.encodeToString(wrap(fresh), Base64.NO_WRAP))
            // commit, not apply: losing this write after the caller has encrypted something
            // with the key would strand that data forever.
            .commit()
        fresh
    }

    /**
     * The Keystore key itself. It is unexportable — `encoded` is null by design — so wrapping
     * goes through a Cipher rather than through [Aead].
     */
    private fun masterKey(): SecretKey = synchronized(this) {
        cachedMaster?.let { return it }
        val existing = (keyStore().getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return (existing ?: generateMasterKey()).also { cachedMaster = it }
    }

    private fun generateMasterKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(Aead.KEY_BYTES * 8)
                    .build(),
            )
        }.generateKey()

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Volatile
    private var cachedMaster: SecretKey? = null

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MASTER_ALIAS = "fg_master"
        const val PREFS = "fg_vault"
        const val KEY_DATABASE = "db"
        const val KEY_BLOB = "blob"
    }
}
