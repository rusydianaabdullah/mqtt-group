package id.larus.mqttgroup

import id.larus.mqttgroup.crypto.Aead
import id.larus.mqttgroup.data.blob.BlobStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlobStoreTest {

    @get:Rule val folder = TemporaryFolder()

    private val key = Aead.randomKey()
    private fun store(key: ByteArray = this.key) = BlobStore(folder.root.resolve("blobs"), key)

    @Test
    fun `round trips a blob`() {
        val store = store()
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val id = store.put(bytes)
        assertTrue(store.exists(id))
        assertArrayEquals(bytes, store.get(id))
    }

    @Test
    fun `nothing readable is left on disk`() {
        val store = store()
        val secret = "the pin is 4815".toByteArray()
        val id = store.put(secret)
        val onDisk = folder.root.resolve("blobs").resolve(id).readBytes()
        assertFalse(String(onDisk).contains("4815"))
        // Nonce and tag are the overhead; anything less would mean it was written in the clear.
        assertEquals(secret.size + Aead.NONCE_BYTES + Aead.TAG_BYTES, onDisk.size)
    }

    @Test
    fun `a different key reads nothing`() {
        val id = store().put("private".toByteArray())
        assertNull(store(Aead.randomKey()).get(id))
    }

    @Test
    fun `a corrupted blob returns null rather than garbage`() {
        val store = store()
        val id = store.put("intact".toByteArray())
        val file = folder.root.resolve("blobs").resolve(id)
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(store.get(id))
    }

    @Test
    fun `missing blobs are absent, not exceptional`() {
        assertNull(store().get("no-such-blob"))
        assertFalse(store().exists("no-such-blob"))
    }

    @Test
    fun `delete and deleteAll clear the directory`() {
        val store = store()
        val first = store.put("a".toByteArray())
        store.put("b".toByteArray())
        assertTrue(store.delete(first))
        assertFalse(store.exists(first))
        store.deleteAll()
        assertEquals(0, folder.root.resolve("blobs").listFiles()!!.size)
    }

    @Test
    fun `digests are over the plaintext, so a receiver can verify what it reassembled`() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            BlobStore.sha256("hello".toByteArray()),
        )
    }
}
