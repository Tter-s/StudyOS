package com.zhixu.studyos.core.ownership

import com.zhixu.studyos.core.backup.BackupValidationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedBackupCryptoTest {
    private val payload = "StudyOS 加密备份内容\n包含中文与换行".toByteArray(Charsets.UTF_8)

    @Test
    fun roundTrip_restoresExactBytes() = runTest {
        val encrypted = ByteArrayOutputStream()
        EncryptedBackupCrypto.encrypt("correct horse battery", encrypted) { output ->
            output.write(payload)
        }
        val cipherText = encrypted.toByteArray()
        assertTrue(String(cipherText, Charsets.ISO_8859_1).startsWith(EncryptedBackupCrypto.MAGIC))
        assertNotEquals(String(payload, Charsets.ISO_8859_1), String(cipherText, Charsets.ISO_8859_1))

        val decrypted = EncryptedBackupCrypto.decrypt(
            "correct horse battery",
            ByteArrayInputStream(cipherText),
        ) { input -> input.readBytes() }
        assertArrayEquals(payload, decrypted)
    }

    @Test(expected = BackupValidationException::class)
    fun wrongPassphrase_isRejected() = runTest {
        val encrypted = ByteArrayOutputStream()
        EncryptedBackupCrypto.encrypt("correct horse battery", encrypted) { output ->
            output.write(payload)
        }
        EncryptedBackupCrypto.decrypt(
            "wrong passphrase",
            ByteArrayInputStream(encrypted.toByteArray()),
        ) { input -> input.readBytes() }
    }

    @Test
    fun shortPassphrase_isRejectedBeforeWriting() = runTest {
        val passed = runCatching {
            EncryptedBackupCrypto.encrypt("short", ByteArrayOutputStream()) { }
        }.exceptionOrNull()
        assertTrue(passed is BackupValidationException)
    }

    @Test
    fun tamperedMagic_isRejected() = runTest {
        val bytes = "NOT-STUDYOS-ENCRYPTED-DATA".toByteArray()
        val error = runCatching {
            EncryptedBackupCrypto.decrypt("correct horse battery", ByteArrayInputStream(bytes)) { }
        }.exceptionOrNull()
        assertTrue(error is BackupValidationException)
    }
}
