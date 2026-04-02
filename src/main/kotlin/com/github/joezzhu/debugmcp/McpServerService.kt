package com.github.joezzhu.debugmcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.*

@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<McpServerService>()
        private const val DEFAULT_PORT = 29190
    }

    private var engine: ApplicationEngine? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val mcpServer = Server(
        serverInfo = Implementation(
            name = "Rider-Debugger-MCP",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    fun start() {
        if (engine != null) {
            LOG.warn("MCP Server is already running")
            return
        }

        LOG.info("Starting Debugger MCP Server on port $DEFAULT_PORT")

        // 注册 MCP Tools
        com.github.joezzhu.debugmcp.api.McpToolRegistrar(project, mcpServer).registerTools()

        coroutineScope.launch {
            try {
                engine = embeddedServer(CIO, port = DEFAULT_PORT, host = "127.0.0.1") {
                    install(ContentNegotiation) {
                        json(McpJson)
                    }
                    mcpStreamableHttp {
                        mcpServer
                    }
                }.start(wait = false)
                LOG.info("MCP Server started successfully on port $DEFAULT_PORT")
            } catch (e: Exception) {
                LOG.error("Failed to start MCP Server", e)
            }
        }
    }

    fun stop() {
        LOG.info("Stopping Debugger MCP Server")
        coroutineScope.cancel()
        engine?.stop(1000, 2000)
        engine = null
    }

    // Called when the service is disposed (e.g., project closed)
    override fun dispose() {
        stop()
    }
}
