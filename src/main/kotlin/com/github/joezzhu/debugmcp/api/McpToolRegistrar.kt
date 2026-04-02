package com.github.joezzhu.debugmcp.api

import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpToolRegistrar(private val project: Project, private val server: Server) {

    private val apiWrapper = DebuggerApiWrapper(project)

    fun registerTools() {
        registerListDebugSessions()
        registerGetStackTrace()
        registerGetVariables()
        registerListThreads()
        registerEvaluateExpression()
        registerListBreakpoints()
    }

    private fun registerListThreads() {
        server.addTool(
            name = "list_threads",
            description = "List all threads in a specific debug session",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string") })
                },
                required = listOf("sessionName")
            )
        ) { request ->
            val sessionName = request.arguments?.get("sessionName")?.toString()?.removeSurrounding("\"") ?: ""
            try {
                val threads = apiWrapper.listThreads(sessionName)
                val resultText = threads.joinToString("\n") { it.displayName }
                CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No threads found." })))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
        }
    }

    private fun registerEvaluateExpression() {
        server.addTool(
            name = "evaluate_expression",
            description = "Evaluate an expression in a specific thread/frame",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string") })
                    put("threadName", buildJsonObject { put("type", "string") })
                    put("frameIndex", buildJsonObject { put("type", "integer") })
                    put("expression", buildJsonObject { put("type", "string") })
                },
                required = listOf("sessionName", "threadName", "frameIndex", "expression")
            )
        ) { request ->
            val sessionName = request.arguments?.get("sessionName")?.toString()?.removeSurrounding("\"") ?: ""
            val threadName = request.arguments?.get("threadName")?.toString()?.removeSurrounding("\"") ?: ""
            val frameIndexStr = request.arguments?.get("frameIndex")?.toString()?.removeSurrounding("\"") ?: "0"
            val expression = request.arguments?.get("expression")?.toString()?.removeSurrounding("\"") ?: ""
            
            try {
                val result = apiWrapper.evaluateExpression(sessionName, threadName, frameIndexStr.toIntOrNull() ?: 0, expression)
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Evaluation error: ${e.message}")), isError = true)
            }
        }
    }

    private fun registerListBreakpoints() {
        server.addTool(
            name = "list_breakpoints",
            description = "List all breakpoints in the project",
            inputSchema = ToolSchema(properties = buildJsonObject {})
        ) { _ ->
            val bps = apiWrapper.getBreakpoints()
            val resultText = bps.joinToString("\n") { "Type: ${it.type.id}, State: ${if(it.isEnabled) "Enabled" else "Disabled"}" }
            CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No breakpoints found." })))
        }
    }

    private fun registerListDebugSessions() {
        server.addTool(
            name = "list_debug_sessions",
            description = "List all active debug sessions",
            inputSchema = ToolSchema(properties = buildJsonObject {})
        ) { _ ->
            val sessions = apiWrapper.getActiveSessions()
            val resultText = sessions.joinToString("\n") { "Session: ${it.sessionName}, Paused: ${it.isPaused}" }
            CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No active debug sessions found." })))
        }
    }

    private fun registerGetStackTrace() {
        server.addTool(
            name = "get_stack_trace",
            description = "Get the stack trace for a specific thread in a debug session",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string"); put("description", "Name of the debug session") })
                    put("threadName", buildJsonObject { put("type", "string"); put("description", "Name of the thread") })
                },
                required = listOf("sessionName", "threadName")
            )
        ) { request ->
            val sessionName = request.arguments?.get("sessionName")?.toString()?.removeSurrounding("\"") ?: ""
            val threadName = request.arguments?.get("threadName")?.toString()?.removeSurrounding("\"") ?: ""
            
            try {
                val frames = apiWrapper.getStackTrace(sessionName, threadName)
                val resultText = frames.mapIndexed { index, frame ->
                    "[$index] ${frame.sourcePosition?.file?.name ?: "Unknown"}:${frame.sourcePosition?.line ?: "Unknown"} - ${frame.toString()}"
                }.joinToString("\n")
                
                CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No stack frames found." })))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error retrieving stack trace: ${e.message}")), isError = true)
            }
        }
    }

    private fun registerGetVariables() {
        server.addTool(
            name = "get_variables",
            description = "Get visible variables for a specific stack frame",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string") })
                    put("threadName", buildJsonObject { put("type", "string") })
                    put("frameIndex", buildJsonObject { put("type", "integer") })
                },
                required = listOf("sessionName", "threadName", "frameIndex")
            )
        ) { request ->
            val sessionName = request.arguments?.get("sessionName")?.toString()?.removeSurrounding("\"") ?: ""
            val threadName = request.arguments?.get("threadName")?.toString()?.removeSurrounding("\"") ?: ""
            val frameIndexStr = request.arguments?.get("frameIndex")?.toString()?.removeSurrounding("\"") ?: "0"
            val frameIndex = frameIndexStr.toIntOrNull() ?: 0
            
            try {
                val variables = apiWrapper.getVariables(sessionName, threadName, frameIndex)
                val resultText = variables.joinToString("\n") { it.toString() }
                CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No variables found." })))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error retrieving variables: ${e.message}")), isError = true)
            }
        }
    }
}
