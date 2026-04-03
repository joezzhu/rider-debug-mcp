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

@Service(Service.Level.PROJECT)
class McpServerService(private val project: Project) : Disposable {

    companion object {
        private val LOG = logger<McpServerService>()
        private const val DEFAULT_PORT = 29190
    }

    private var engine: EmbeddedServer<*, *>? = null

    val mcpServer: Server by lazy {
        Server(
            serverInfo = Implementation(
                name = "Rider-Debugger-MCP",
                version = "0.3.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )
    }

    fun start() {
        if (engine != null) {
            LOG.warn("[DebugMCP] MCP Server is already running, skipping start")
            return
        }

        try {
            LOG.info("[DebugMCP] Registering MCP Tools...")
            com.github.joezzhu.debugmcp.api.McpToolRegistrar(project, mcpServer).registerTools()
            LOG.info("[DebugMCP] MCP Tools registered")
        } catch (e: Throwable) {
            LOG.error("[DebugMCP] Failed to register MCP Tools", e)
            return
        }

        LOG.info("[DebugMCP] Starting Ktor server on 127.0.0.1:$DEFAULT_PORT ...")
        Thread {
            try {
                val srv = embeddedServer(CIO, port = DEFAULT_PORT, host = "127.0.0.1") {
                    install(ContentNegotiation) {
                        json(McpJson)
                    }
                    mcpStreamableHttp {
                        mcpServer
                    }
                }
                srv.start(wait = false)
                engine = srv
                LOG.info("[DebugMCP] MCP Server started successfully on port $DEFAULT_PORT")
            } catch (e: Throwable) {
                LOG.error("[DebugMCP] Failed to start Ktor server", e)
            }
        }.apply {
            name = "DebugMCP-Server-Starter"
            isDaemon = true
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, ex ->
                LOG.error("[DebugMCP] Uncaught exception in thread ${t.name}", ex)
            }
        }.start()
    }

    fun stop() {
        try {
            engine?.stop(1000, 2000)
            engine = null
            LOG.info("[DebugMCP] MCP Server stopped")
        } catch (e: Throwable) {
            LOG.error("[DebugMCP] Error stopping MCP Server", e)
        }
    }

    override fun dispose() {
        stop()
    }
}
