package id.larus.mqttgroup

import id.larus.mqttgroup.data.backup.BackupArchive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup container format.
 *
 * Worth testing carefully because every failure here is discovered at the worst possible moment:
 * on a new phone, with the old one gone. A backup that cannot be opened is worse than no backup,
 * because the user stopped worrying about it.
 *
 * These run at the real iteration count. It is tuned to about a second on a phone, which on a
 * desktop JVM is fast enough that a dozen derivations still finish in a couple of seconds — and
 * testing the shipped cost is worth more than the time it saves to fake a cheaper one.
 */
class BackupArchiveTest {

    private val payload = JSONObject().apply {
        put("version", 1)
        put("hello", "dunia")
    }

    private fun pass(text: String) = text.toCharArray()

    @Test
    fun `round trips through the right passphrase`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val opened = BackupArchive.open(sealed, pass("correct horse battery staple")).getOrThrow()

        assertEquals("dunia", opened.getString("hello"))
    }

    @Test
    fun `a wrong passphrase is refused, and named as such`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val failure = BackupArchive.open(sealed, pass("incorrect horse")).failure()

        assertEquals(BackupArchive.Failure.WrongPassphrase, failure)
    }

    @Test
    fun `a file that is not a backup is told apart from a wrong passphrase`() {
        // The distinction matters: one means "try again", the other means "wrong file", and a
        // single "failed" leaves someone retyping a correct passphrase forever.
        val failure = BackupArchive.open("just some bytes, not a backup".toByteArray(), pass("x")).failure()

        assertEquals(BackupArchive.Failure.NotABackup, failure)
    }

    @Test
    fun `a truncated file is not mistaken for a wrong passphrase`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val failure = BackupArchive.open(sealed.copyOfRange(0, 10), pass("correct horse battery staple")).failure()

        assertEquals(BackupArchive.Failure.NotABackup, failure)
    }

    @Test
    fun `tampering with the iteration count is rejected, not honoured`() {
        // The attack this defends against: rewrite the header to say one iteration, hand the
        // file back, and let the victim's own app do the cheap key derivation. The header is
        // authenticated as AAD, so the tag stops it.
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val tampered = sealed.copyOf()
        tampered[5] = 0; tampered[6] = 0; tampered[7] = 0; tampered[8] = 1

        val failure = BackupArchive.open(tampered, pass("correct horse battery staple")).failure()
        assertEquals(BackupArchive.Failure.WrongPassphrase, failure)
    }

    @Test
    fun `an absurd iteration count is refused rather than attempted`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val tampered = sealed.copyOf()
        // 0x7FFFFFFF iterations would hang the phone for days.
        tampered[5] = 0x7F; tampered[6] = -1; tampered[7] = -1; tampered[8] = -1

        assertEquals(BackupArchive.Failure.NotABackup, BackupArchive.open(tampered, pass("x")).failure())
    }

    @Test
    fun `tampering with the ciphertext is caught`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        assertEquals(
            BackupArchive.Failure.WrongPassphrase,
            BackupArchive.open(tampered, pass("correct horse battery staple")).failure(),
        )
    }

    @Test
    fun `two exports of the same data differ, so the file leaks nothing by comparison`() {
        val a = BackupArchive.seal(payload, pass("correct horse battery staple"))
        val b = BackupArchive.seal(payload, pass("correct horse battery staple"))

        // Fresh salt and fresh nonce each time. Identical bytes would tell an observer holding
        // two backups that nothing had changed between them.
        assertNotEquals(a.toList(), b.toList())
        assertTrue(BackupArchive.open(b, pass("correct horse battery staple")).isSuccess)
    }

    @Test
    fun `the plaintext is not sitting in the file`() {
        val sealed = BackupArchive.seal(payload, pass("correct horse battery staple"))
        assertTrue("dunia" !in String(sealed, Charsets.ISO_8859_1))
    }

    private fun Result<JSONObject>.failure(): BackupArchive.Failure =
        (exceptionOrNull() as BackupArchive.BackupException).failure
}
