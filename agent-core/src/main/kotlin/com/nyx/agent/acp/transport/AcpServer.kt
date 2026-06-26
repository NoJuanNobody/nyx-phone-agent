package com.nyx.agent.acp.transport

import com.nyx.agent.acp.auth.HmacAuthenticator
import com.nyx.agent.acp.schema.ACPMessage
import com.nyx.agent.acp.schema.AcpSchema
import com.nyx.agent.acp.schema.ErrorCode
import com.nyx.agent.acp.schema.MessageType
import com.nyx.agent.acp.schema.ProtocolVersion
import com.nyx.agent.acp.schema.ResponseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import java.net.StandardSocketOptions
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousChannelGroup
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Running state of the ACP server.
 */
enum class AcpServerState {
    STOPPED, STARTING, RUNNING, SHUTTING_DOWN
}

/**
 * Configuration for the ACP server.
 *
 * @property maxConcurrentClients Maximum number of simultaneously connected clients (default 200).
 * @property heartbeatTimeoutMs Max time to respond to a HEARTBEAT (default 200ms).
 * @property gracefulShutdownTimeoutMs Max time to wait for in-flight requests during shutdown (default 5000ms).
 * @property socketConfig Socket path selection configuration.
 */
data class AcpServerConfig(
    val maxConcurrentClients: Int = 200,
    val heartbeatTimeoutMs: Long = 200L,
    val gracefulShutdownTimeoutMs: Long = 5000L,
    val socketConfig: AcpSocketConfig = AcpSocketConfig()
)

/**
 * The ACP server. Provides a Unix Domain Socket listener that accepts
 * ACP messages, validates them, authenticates via HMAC, and dispatches
 * to a handler.
 *
 * Lifecycle:
 *   1. [start] — binds the socket, begins accepting clients.
 *   2. [stop] — gracefully shuts down: stops accepting new clients, waits
 *      for in-flight requests to complete or time out, then closes the socket.
 *
 * Each client connection gets its own coroutine. The server tracks active
 * connections and enforces [AcpServerConfig.maxConcurrentClients].
 *
 * @property authenticator HMAC authenticator for signature verification.
 * @property messageHandler Called for each valid, authenticated non-heartbeat message.
 * @property config Server configuration.
 */
class AcpServer(
    private val authenticator: HmacAuthenticator,
    private val messageHandler: suspend (ACPMessage) -> JsonElement,
    private val config: AcpServerConfig = AcpServerConfig()
) {
    private val logger = LoggerFactory.getLogger("AcpServer")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(AcpServerState.STOPPED)
    val state: StateFlow<AcpServerState> = _state.asStateFlow()

    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    /** Events emitted by the server (e.g., security audit, client connect/disconnect). */
    private val _events = MutableSharedFlow<AcpServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<AcpServerEvent> = _events.asSharedFlow()

    private var serverSocket: ServerSocketChannel? = null
    private var socketAddress: AcpSocketAddress? = null
    private var serverScope: CoroutineScope? = null
    private val inFlightRequests = AtomicInteger(0)

    /**
     * Start the server. Binds the Unix Domain Socket and begins accepting
     * clients in a background coroutine.
     *
     * @return The resolved [AcpSocketAddress].
     * @throws IllegalStateException if already running.
     * @throws IOException if the socket cannot be bound.
     */
    fun start(): AcpSocketAddress {
        check(_state.value == AcpServerState.STOPPED) { "Server already started" }
        _state.value = AcpServerState.STARTING

        val address = AcpSocketFactory.resolve(config.socketConfig)
        socketAddress = address

        when (address) {
            is AcpSocketAddress.FileSystem -> {
                serverSocket = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                serverSocket!!.configureBlocking(true)
                serverSocket!!.bind(address.address)
            }
            is AcpSocketAddress.AbstractNamespace -> {
                serverSocket = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
                serverSocket!!.configureBlocking(true)
                serverSocket!!.bind(address.address)
            }
        }

        _state.value = AcpServerState.RUNNING

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        scope.launch { acceptLoop() }

        logger.info("ACP server started on {}", address)
        return address
    }

    /**
     * Accept loop: spawns a coroutine per client.
     */
    private suspend fun acceptLoop() {
        val server = serverSocket ?: return
        val scope = serverScope ?: return

        while (scope.isActive && _state.value == AcpServerState.RUNNING) {
            try {
                val clientChannel = server.accept()
                if (_activeConnections.value >= config.maxConcurrentClients) {
                    // Reject: too many clients
                    logger.warn("Rejecting client: maxConcurrentClients={}", config.maxConcurrentClients)
                    clientChannel.close()
                    continue
                }
                scope.launch { handleClient(clientChannel) }
            } catch (e: IOException) {
                if (_state.value == AcpServerState.RUNNING) {
                    logger.error("Accept error", e)
                }
            }
        }
    }

    /**
     * Handle a single client connection.
     *
     * Reads newline-delimited JSON messages, processes each one, and writes
     * a response. The connection stays open until the client disconnects
     * or the server shuts down.
     */
    private suspend fun handleClient(channel: SocketChannel) {
        _activeConnections.value++
        val clientId = "conn-${UUID.randomUUID()}"
        _events.tryEmit(AcpServerEvent.ClientConnected(clientId))

        try {
            channel.configureBlocking(true)
            val buffer = ByteBuffer.allocate(65536)
            val lineBuffer = StringBuilder()

            while (serverScope?.isActive == true && _state.value == AcpServerState.RUNNING) {
                buffer.clear()
                val read = withContext(Dispatchers.IO) {
                    try { channel.read(buffer) } catch (e: IOException) { -1 }
                }
                if (read == -1) break
                if (read == 0) continue

                buffer.flip()
                val chunk = Charsets.UTF_8.decode(buffer).toString()
                lineBuffer.append(chunk)

                // Process complete lines
                while (true) {
                    val nl = lineBuffer.indexOf('\n')
                    if (nl == -1) break
                    val line = lineBuffer.substring(0, nl)
                    lineBuffer.delete(0, nl + 1)
                    if (line.isBlank()) continue
                    processLine(channel, line, clientId)
                }
            }
        } catch (e: Exception) {
            logger.warn("Client {} error: {}", clientId, e.message)
        } finally {
            _activeConnections.value--
            _events.tryEmit(AcpServerEvent.ClientDisconnected(clientId))
            try { channel.close() } catch (_: IOException) {}
        }
    }

    /**
     * Process a single line (one ACP message).
     */
    private suspend fun processLine(channel: SocketChannel, line: String, clientId: String) {
        // 1. Schema validation
        val validationResult = AcpSchema.validate(line)
        if (validationResult is AcpSchema.ValidationResult.Invalid) {
            val errorResp = buildErrorResponse(
                requestId = "unknown",
                clientId = "server",
                version = ProtocolVersion.CURRENT.toString(),
                error = validationResult.error
            )
            writeMessage(channel, errorResp)
            return
        }

        val message = (validationResult as AcpSchema.ValidationResult.Valid).message

        // 2. Protocol version negotiation
        val clientVersion = message.protocolVersion()
        if (!ProtocolVersion.CURRENT.isCompatibleWith(clientVersion)) {
            val errorResp = buildErrorResponse(
                requestId = message.requestId,
                clientId = "server",
                version = ProtocolVersion.CURRENT.toString(),
                error = com.nyx.agent.acp.schema.ErrorResponse(
                    ErrorCode.VERSION_MISMATCH.code,
                    "Incompatible protocol version: client=$clientVersion, server=${ProtocolVersion.CURRENT}"
                )
            )
            writeMessage(channel, errorResp)
            return
        }

        // 3. HMAC verification (skip for heartbeat? No — all messages must be signed)
        if (!authenticator.verify(message)) {
            // Security audit event already emitted by authenticator
            val errorResp = buildErrorResponse(
                requestId = message.requestId,
                clientId = "server",
                version = ProtocolVersion.CURRENT.toString(),
                error = com.nyx.agent.acp.schema.ErrorResponse(
                    ErrorCode.UNAUTHORIZED.code,
                    "Signature verification failed"
                )
            )
            writeMessage(channel, errorResp)
            return
        }

        // 4. Dispatch by message type
        inFlightRequests.incrementAndGet()
        try {
            when (message.messageType) {
                MessageType.HEARTBEAT -> {
                    // Respond immediately within timeout
                    val response = buildHeartbeatResponse(message)
                    writeMessage(channel, response)
                }
                MessageType.REQUEST, MessageType.EVENT -> {
                    val result = withTimeoutOrNull(config.gracefulShutdownTimeoutMs) {
                        messageHandler(message)
                    }
                    val response = if (result != null) {
                        buildOkResponse(message, result)
                    } else {
                        buildErrorResponse(
                            requestId = message.requestId,
                            clientId = "server",
                            version = ProtocolVersion.CURRENT.toString(),
                            error = com.nyx.agent.acp.schema.ErrorResponse(
                                ErrorCode.TIMEOUT.code,
                                "Request timed out"
                            )
                        )
                    }
                    writeMessage(channel, response)
                }
                MessageType.RESPONSE -> {
                    // Responses from clients are not expected but we acknowledge
                    logger.debug("Received RESPONSE from {}: {}", clientId, message.requestId)
                }
            }
        } finally {
            inFlightRequests.decrementAndGet()
        }
    }

    private fun buildHeartbeatResponse(request: ACPMessage): ACPMessage {
        val payload = buildJsonObject {
            put("status", "alive")
            put("serverTime", Instant.now().toEpochMilli())
            put("activeConnections", _activeConnections.value)
        }
        return signMessage(
            ACPMessage(
                version = ProtocolVersion.CURRENT.toString(),
                timestamp = Instant.now().toEpochMilli(),
                requestId = request.requestId,
                clientId = "server",
                messageType = MessageType.RESPONSE,
                status = ResponseStatus.OK,
                payload = payload,
                signature = ""
            )
        )
    }

    private fun buildOkResponse(request: ACPMessage, result: JsonElement): ACPMessage {
        return signMessage(
            ACPMessage(
                version = ProtocolVersion.CURRENT.toString(),
                timestamp = Instant.now().toEpochMilli(),
                requestId = request.requestId,
                clientId = "server",
                messageType = MessageType.RESPONSE,
                status = ResponseStatus.OK,
                payload = result,
                signature = ""
            )
        )
    }

    private fun buildErrorResponse(
        requestId: String,
        clientId: String,
        version: String,
        error: com.nyx.agent.acp.schema.ErrorResponse
    ): ACPMessage {
        val payload = Json.encodeToJsonElement(
            com.nyx.agent.acp.schema.ErrorResponse.serializer(),
            error
        )
        return signMessage(
            ACPMessage(
                version = version,
                timestamp = Instant.now().toEpochMilli(),
                requestId = requestId,
                clientId = clientId,
                messageType = MessageType.RESPONSE,
                status = ResponseStatus.ERROR,
                payload = payload,
                signature = ""
            )
        )
    }

    private fun signMessage(message: ACPMessage): ACPMessage {
        return authenticator.signMessage(message)
    }

    /**
     * Write a message to the channel as JSON + newline.
     */
    private suspend fun writeMessage(channel: SocketChannel, message: ACPMessage) {
        val jsonStr = json.encodeToString(ACPMessage.serializer(), message) + "\n"
        val bytes = jsonStr.toByteArray(Charsets.UTF_8)
        withContext(Dispatchers.IO) {
            try {
                channel.write(ByteBuffer.wrap(bytes))
            } catch (e: IOException) {
                logger.warn("Write error: {}", e.message)
            }
        }
    }

    /**
     * Gracefully shut down the server.
     *
     * - Stops accepting new connections.
     * - Waits for in-flight requests to complete or timeout (up to [AcpServerConfig.gracefulShutdownTimeoutMs]).
     * - Closes all client connections.
     * - Closes and unlinks the socket file (for filesystem sockets).
     */
    suspend fun stop() {
        if (_state.value == AcpServerState.STOPPED || _state.value == AcpServerState.SHUTTING_DOWN) return
        _state.value = AcpServerState.SHUTTING_DOWN
        logger.info("ACP server shutting down...")

        // Wait for in-flight requests
        val deadline = System.currentTimeMillis() + config.gracefulShutdownTimeoutMs
        while (inFlightRequests.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        if (inFlightRequests.get() > 0) {
            logger.warn("{} in-flight requests timed out during shutdown", inFlightRequests.get())
        }

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            logger.warn("Error closing server socket", e)
        }

        // Cleanup socket file
        val addr = socketAddress
        if (addr is AcpSocketAddress.FileSystem) {
            try {
                val file = addr.path.toFile()
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                logger.warn("Error cleaning up socket file", e)
            }
        }

        // Cancel all coroutines (cancelAndJoin is a Job operation, not a CoroutineScope one)
        serverScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        serverScope = null
        serverSocket = null
        socketAddress = null
        _state.value = AcpServerState.STOPPED
        logger.info("ACP server stopped")
    }
}

/**
 * Events emitted by [AcpServer].
 */
sealed class AcpServerEvent {
    data class ClientConnected(val clientId: String) : AcpServerEvent()
    data class ClientDisconnected(val clientId: String) : AcpServerEvent()
    data class SecurityAudit(val requestId: String, val clientId: String, val reason: String) : AcpServerEvent()
    data class MessageProcessed(val requestId: String, val messageType: MessageType) : AcpServerEvent()
}
