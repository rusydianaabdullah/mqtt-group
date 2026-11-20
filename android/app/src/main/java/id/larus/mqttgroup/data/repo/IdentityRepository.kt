package id.larus.mqttgroup.data.repo

import id.larus.mqttgroup.data.blob.BlobStore
import id.larus.mqttgroup.data.db.AppDatabase
import id.larus.mqttgroup.data.db.IdentityEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Who this phone is. The user id is generated once, on first launch, and is the identity every
 * group sees — there is no account and no server to register with, so this random UUID is the
 * whole of it.
 */
class IdentityRepository(
    private val db: AppDatabase,
    private val blobs: BlobStore,
) {
    fun observe(): Flow<IdentityEntity?> = db.identity().observe()

    suspend fun get(): IdentityEntity? = db.identity().get()

    /**
     * Returns this phone's identity, creating it if this is the first launch. The name starts
     * empty; onboarding is what fills it in.
     */
    suspend fun ensure(): IdentityEntity =
        db.identity().get() ?: IdentityEntity(
            userId = UUID.randomUUID().toString(),
            displayName = "",
            avatarBlobId = null,
            createdAt = System.currentTimeMillis(),
        ).also { db.identity().put(it) }

    suspend fun setDisplayName(name: String) {
        val current = ensure()
        db.identity().put(current.copy(displayName = name.trim()))
    }

    /** Replaces the avatar, deleting the previous blob so old photos do not accumulate. */
    suspend fun setAvatar(bytes: ByteArray?) {
        val current = ensure()
        val blobId = bytes?.let { blobs.put(it) }
        current.avatarBlobId?.let(blobs::delete)
        db.identity().put(current.copy(avatarBlobId = blobId))
    }

    fun avatar(blobId: String): ByteArray? = blobs.get(blobId)
}
