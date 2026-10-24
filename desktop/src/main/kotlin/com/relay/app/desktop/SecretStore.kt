package com.relay.app.desktop

import com.github.javakeyring.Keyring
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * Desktop secret storage seam (LP-D05). It holds one 256-bit master key per logical secret id;
 * callers derive/seal everything else with it. The keyring-backed implementation uses the OS
 * secret service (GNOME Keyring / KWallet through java-keyring); [FileSecretStore] is the
 * documented fallback when no keyring is reachable.
 */
interface SecretStore {
    /** Human-readable backend name for logs/evidence. Never includes a secret. */
    val backend: String

    /** Returns the existing 32-byte key for [id], or creates and stores one. */
    fun getOrCreateKey(id: String): ByteArray
}

/** Linux Secret Service / macOS Keychain through java-keyring. */
class KeyringSecretStore(
    private val service: String = "relay-desktop",
) : SecretStore {

    override val backend: String = "java-keyring (OS secret service)"

    private val random = SecureRandom()

    override fun getOrCreateKey(id: String): ByteArray {
        Keyring.create().use { keyring ->
            val account = "relay-$id"
            val existing = runCatching { keyring.getPassword(service, account) }.getOrNull()
            if (existing != null) {
                val decoded = runCatching { Base64.getDecoder().decode(existing) }.getOrNull()
                if (decoded != null && decoded.size == 32) return decoded
            }
            val key = ByteArray(32).also(random::nextBytes)
            keyring.setPassword(service, account, Base64.getEncoder().encodeToString(key))
            // Read back so a backend that silently accepts writes but cannot read is not trusted.
            val readBack = keyring.getPassword(service, account)
            check(readBack != null && readBack == Base64.getEncoder().encodeToString(key)) {
                "Secret service did not persist the desktop key"
            }
            return key
        }
    }

    /** Cheap probe used by [SecretStores.open] to decide whether the keyring is usable. */
    fun probe(): Boolean = runCatching {
        Keyring.create().use { keyring ->
            val account = "relay-probe"
            keyring.setPassword(service, account, "ok")
            val read = keyring.getPassword(service, account)
            keyring.deletePassword(service, account)
            read == "ok"
        }
    }.getOrDefault(false)
}

/**
 * File-based fallback: a 32-byte master key in a 0600 file under the Relay config directory.
 *
 * Honest limitation: this protects against other OS users and accidental exposure, not against
 * malware or anyone who can read the logged-in user's files. The database encryption key is only
 * as strong as this file's permissions. The UI/evidence states that plainly; it is not equivalent
 * to an OS keyring.
 */
class FileSecretStore(private val file: Path) : SecretStore {

    override val backend: String = "file fallback (0600)"

    private val random = SecureRandom()
    private val lock = Any()

    override fun getOrCreateKey(id: String): ByteArray = synchronized(lock) {
        val existing = DesktopPaths.readPrivateFile(file)
        if (existing != null) {
            val text = existing.toString(Charsets.UTF_8).trim()
            val decoded = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            require(decoded != null && decoded.size == 32) {
                "Secret file $file is corrupt; delete it to re-enrol (this loses access to existing data)"
            }
            require(DesktopPaths.isPrivate(file)) { "Secret file $file is not 0600" }
            return@synchronized decoded
        }
        val key = ByteArray(32).also(random::nextBytes)
        DesktopPaths.writePrivateFile(file, Base64.getEncoder().encodeToString(key).toByteArray(Charsets.UTF_8))
        require(DesktopPaths.isPrivate(file)) { "Could not set 0600 on secret file $file" }
        key
    }
}

object SecretStores {
    /**
     * Prefers the OS keyring. Set `RELAY_FORCE_FILE_SECRET_STORE=1` to force the file fallback
     * (used by tests and headless runs without a Secret Service).
     */
    fun open(paths: DesktopPaths): SecretStore {
        if (System.getenv("RELAY_FORCE_FILE_SECRET_STORE") != "1") {
            val keyring = KeyringSecretStore()
            if (keyring.probe()) return keyring
        }
        return FileSecretStore(paths.secretFile)
    }
}
