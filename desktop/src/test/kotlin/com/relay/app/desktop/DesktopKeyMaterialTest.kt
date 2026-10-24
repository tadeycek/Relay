package com.relay.app.desktop

import com.relay.app.messaging.InnerEnvelope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopKeyMaterialTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(): DesktopPaths =
        DesktopPaths(tmp.newFolder("config").toPath(), tmp.newFolder("data").toPath())

    @Test
    fun `encrypts and decrypts with its own key file`() {
        val paths = paths()
        val secrets = FileSecretStore(paths.secretFile)
        val material = DesktopKeyMaterial(paths, secrets)
        val publicKey = material.publicKeyBase64()

        val ciphertext = material.encryptTo(publicKey, "hello HPKE".toByteArray())
        assertNotNull(ciphertext)
        assertArrayEquals("hello HPKE".toByteArray(), material.decryptMine(ciphertext!!))

        // The key file must exist, be 0600 and not contain the raw Tink keyset JSON.
        assertTrue(java.nio.file.Files.isRegularFile(paths.keyFile))
        assertTrue(DesktopPaths.isPrivate(paths.keyFile))
        val raw = String(java.nio.file.Files.readAllBytes(paths.keyFile), Charsets.UTF_8)
        assertFalse(raw.contains("primaryKeyId"))
        assertFalse(raw.contains("keyData"))
    }

    @Test
    fun `reopens with the same secret and still decrypts`() {
        val paths = paths()
        val secrets = FileSecretStore(paths.secretFile)
        val material = DesktopKeyMaterial(paths, secrets)
        val ciphertext = material.encryptTo(material.publicKeyBase64(), "persist me".toByteArray())!!

        val reopened = DesktopKeyMaterial(paths, FileSecretStore(paths.secretFile))
        assertArrayEquals("persist me".toByteArray(), reopened.decryptMine(ciphertext))
    }

    @Test
    fun `wrong secret store cannot open the key file`() {
        val paths = paths()
        DesktopKeyMaterial(paths, FileSecretStore(paths.secretFile)).publicKeyBase64()
        val wrong = FileSecretStore(tmp.newFolder("wrong").toPath().resolve("secret.key"))
        assertThrows(Exception::class.java) {
            DesktopKeyMaterial(paths, wrong).publicKeyBase64()
        }
    }

    @Test
    fun `rotation keeps the previous key usable during the grace period`() {
        val paths = paths()
        val material = DesktopKeyMaterial(paths, FileSecretStore(paths.secretFile), clock = { 1_000L })
        val oldPublic = material.publicKeyBase64()
        val oldCiphertext = material.encryptTo(oldPublic, "before rotation".toByteArray())!!

        val newPublic = material.rotate()
        assertNotNull(newPublic)
        assertFalse(newPublic == oldPublic)
        // In-flight message to the retired key still opens.
        assertArrayEquals("before rotation".toByteArray(), material.decryptMine(oldCiphertext))
        // New messages use the new key.
        assertArrayEquals("after rotation".toByteArray(), material.decryptMine(material.encryptTo(newPublic!!, "after rotation".toByteArray())!!))
    }

    @Test
    fun `inner envelope seals and opens text with the desktop key`() {
        val paths = paths()
        val material = DesktopKeyMaterial(paths, FileSecretStore(paths.secretFile))
        val sealed = InnerEnvelope.seal(material, material.publicKeyBase64(), "codec parity")
        assertTrue(sealed.startsWith("TYPE:ENC|CT:"))
        assertEquals("codec parity", InnerEnvelope.open(material, sealed))
    }

    @Test
    fun `decryptMine returns null for a foreign ciphertext`() {
        val aPaths = DesktopPaths(tmp.newFolder("a-config").toPath(), tmp.newFolder("a-data").toPath())
        val bPaths = DesktopPaths(tmp.newFolder("b-config").toPath(), tmp.newFolder("b-data").toPath())
        val a = DesktopKeyMaterial(aPaths, FileSecretStore(aPaths.secretFile))
        val b = DesktopKeyMaterial(bPaths, FileSecretStore(bPaths.secretFile))
        val foreign = b.encryptTo(b.publicKeyBase64(), "for b".toByteArray())!!
        assertNull(a.decryptMine(foreign))
    }
}
