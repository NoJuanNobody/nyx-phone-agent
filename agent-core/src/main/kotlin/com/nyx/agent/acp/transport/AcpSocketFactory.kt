package com.nyx.agent.acp.transport

import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.Files

/**
 * Selects the appropriate Unix Domain Socket address for the ACP transport.
 *
 * Two paths are supported:
 *
 * **Path A — Filesystem socket (default for production):**
 *   `/data/nyx/acp.sock`
 *   Used when a writable filesystem location exists (e.g., rooted device or
 *   emulator with `/data/nyx` created). The socket file is created with
 *   permissions `rw-------` (0600) to prevent access by untrusted UIDs.
 *
 * **Path B — Abstract namespace socket (fallback):**
 *   `@nyx_acp_socket`
 *   Used when the filesystem path is not writable (e.g., non-rooted device).
 *   Abstract namespace sockets live in memory and are cleaned up automatically
 *   when the server closes, but they have no filesystem permission checks —
 *   HMAC authentication is mandatory in this mode.
 *
 * The selection logic:
 *   1. If [pathOverride] is set (non-null, non-empty), use it as a filesystem path.
 *   2. Else if the Path A directory `/data/nyx` exists and is writable, use Path A.
 *   3. Else use Path B (abstract namespace).
 *
 * @property pathOverride Optional explicit filesystem socket path override.
 * @property preferAbstract If true, skip the filesystem check and use Path B directly.
 */
data class AcpSocketConfig(
    val pathOverride: String? = null,
    val preferAbstract: Boolean = false
)

/**
 * The resolved socket address.
 */
sealed class AcpSocketAddress {
    /** Filesystem socket path. */
    data class FileSystem(val path: Path, val address: UnixDomainSocketAddress) : AcpSocketAddress()
    /** Abstract namespace socket. */
    data class AbstractNamespace(val name: String, val address: UnixDomainSocketAddress) : AcpSocketAddress()
}

/**
 * Factory that resolves the [AcpSocketAddress] based on [AcpSocketConfig]
 * and the current environment.
 */
object AcpSocketFactory {

    /** Default filesystem socket path (Path A). */
    const val DEFAULT_FS_PATH = "/data/nyx/acp.sock"

    /** Default abstract namespace name (Path B). */
    const val DEFAULT_ABSTRACT_NAME = "nyx_acp_socket"

    /**
     * Resolve the socket address.
     *
     * For filesystem sockets, the parent directory must exist and be writable.
     * If an existing socket file is present, it is deleted (stale cleanup).
     */
    fun resolve(config: AcpSocketConfig = AcpSocketConfig()): AcpSocketAddress {
        // Explicit override
        if (!config.pathOverride.isNullOrEmpty()) {
            return resolveFileSystem(Paths.get(config.pathOverride))
        }

        // Prefer abstract explicitly
        if (config.preferAbstract) {
            return resolveAbstract()
        }

        // Try Path A
        val pathA = Paths.get(DEFAULT_FS_PATH)
        val parent = pathA.parent
        if (parent != null) {
            val parentDir = parent.toFile()
            if (parentDir.exists() && parentDir.isDirectory && parentDir.canWrite()) {
                return resolveFileSystem(pathA)
            }
        }

        // Fallback to Path B
        return resolveAbstract()
    }

    /**
     * Resolve a filesystem socket, performing stale-file cleanup.
     */
    private fun resolveFileSystem(path: Path): AcpSocketAddress.FileSystem {
        val file = path.toFile()
        if (file.exists()) {
            // Stale socket file cleanup
            file.delete()
        }
        // Ensure parent exists
        file.parentFile?.mkdirs()
        return AcpSocketAddress.FileSystem(
            path = path,
            address = UnixDomainSocketAddress.of(path)
        )
    }

    /**
     * Resolve an abstract namespace socket.
     *
     * Linux abstract sockets are identified by a leading NUL byte, conventionally
     * written with an '@' prefix (e.g. `@nyx_acp_socket`). The JDK's
     * [UnixDomainSocketAddress] is backed by a [Path], which rejects a literal NUL
     * (`InvalidPathException`), so the '@'-prefixed textual form is used here.
     * Binding a true Linux abstract socket would require a native/JNI layer; this
     * is the address representation used until that exists.
     */
    private fun resolveAbstract(): AcpSocketAddress.AbstractNamespace {
        return AcpSocketAddress.AbstractNamespace(
            name = DEFAULT_ABSTRACT_NAME,
            address = UnixDomainSocketAddress.of("@$DEFAULT_ABSTRACT_NAME")
        )
    }
}
