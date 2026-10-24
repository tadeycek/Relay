package com.relay.app.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * XDG locations for the desktop client. Everything Relay writes lives under one config directory
 * (keys, identity, database) and one data directory (attachments, future exports).
 *
 * [configDir] and [dataDir] are injectable so tests never touch the user's real profile.
 */
class DesktopPaths(
    configDir: Path? = null,
    dataDir: Path? = null,
) {
    val configDir: Path = (configDir ?: defaultConfigDir()).also { secureDirectory(it) }
    val dataDir: Path = (dataDir ?: defaultDataDir()).also { secureDirectory(it) }

    val keyFile: Path get() = configDir.resolve("keymaterial.json")
    val identityFile: Path get() = configDir.resolve("identity.json")
    val databaseFile: Path get() = configDir.resolve("relay.db")
    val secretFile: Path get() = configDir.resolve("secret.key")

    companion object {
        fun defaultConfigDir(): Path {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.config")
            return Path.of(base, "relay")
        }

        fun defaultDataDir(): Path {
            val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return Path.of(base, "relay")
        }

        /** Creates [dir] (0700) and refuses to follow a symlink at the final component. */
        fun secureDirectory(dir: Path): Path {
            Files.createDirectories(dir)
            if (Files.isSymbolicLink(dir)) error("Refusing to use symlinked Relay directory: $dir")
            runCatching {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
            }
            return dir
        }

        /**
         * Writes [bytes] to [file] atomically with 0600 permissions. The temp file is created with
         * 0600 before any content is written, so a crash cannot leave a readable partial secret.
         */
        fun writePrivateFile(file: Path, bytes: ByteArray) {
            val parent = file.parent ?: error("File has no parent: $file")
            secureDirectory(parent)
            val tmp = Files.createTempFile(parent, ".${file.fileName}.", ".tmp", PosixFilePermissions.asFileAttribute(privatePermissions()))
            try {
                Files.write(tmp, bytes)
                setPrivate(tmp)
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                setPrivate(file)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

        fun readPrivateFile(file: Path): ByteArray? =
            if (Files.isRegularFile(file)) Files.readAllBytes(file) else null

        fun setPrivate(file: Path) {
            runCatching { Files.setPosixFilePermissions(file, privatePermissions()) }
                .onFailure {
                    // Non-POSIX filesystem: at least remove group/other access where the JDK can.
                    file.toFile().setReadable(false, false)
                    file.toFile().setWritable(false, false)
                    file.toFile().setReadable(true, true)
                    file.toFile().setWritable(true, true)
                }
        }

        private fun privatePermissions(): Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")

        /** True when [file] is owner-only readable/writable (or the platform has no POSIX view). */
        fun isPrivate(file: Path): Boolean = runCatching {
            Files.getPosixFilePermissions(file) == privatePermissions()
        }.getOrDefault(true)
    }
}
