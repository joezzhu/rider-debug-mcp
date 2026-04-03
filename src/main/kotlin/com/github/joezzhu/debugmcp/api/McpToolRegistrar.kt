package com.github.joezzhu.debugmcp.api

import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpToolRegistrar(private val project: Project, private val server: Server) {

    private val apiWrapper = DebuggerApiWrapper(project)

    fun registerTools() {
        registerListDebugSessions()
        registerGetDebugSessionStatus()
        registerGetStackTrace()
        registerGetVariables()
        registerListThreads()
        registerEvaluateExpression()
        registerGetSourceContext()
        registerListBreakpoints()
    }

    private fun registerListDebugSessions() {
        server.addTool(
            name = "list_debug_sessions",
            description = "List all active debug sessions",
            inputSchema = ToolSchema(properties = buildJsonObject {})
        ) { _ ->
            val sessions = apiWrapper.getActiveSessions()
            val resultText = sessions.joinToString("\n\n") { session ->
                apiWrapper.getDebugSessionStatus(session.sessionName)?.toDisplayString()
                    ?: "session: ${session.sessionName}\nstatus: unavailable"
            }
            CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No active debug sessions found." })))
        }
    }

    private fun registerGetDebugSessionStatus() {
        server.addTool(
            name = "get_debug_session_status",
            description = "Get a concise status summary for a debug session",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string") })
                },
                required = listOf("sessionName")
            )
        ) { request ->
            val sessionName = request.stringArg("sessionName")
            val summary = apiWrapper.getDebugSessionStatus(sessionName)
            CallToolResult(
                content = listOf(
                    TextContent(summary?.toDisplayString() ?: "Debug session not found: $sessionName")
                ),
                isError = summary == null
            )
        }
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
            val sessionName = request.stringArg("sessionName")
            try {
                val threads = apiWrapper.listThreads(sessionName)
                val resultText = threads.mapIndexed { index, thread ->
                    "[$index] ${thread.displayName}"
                }.joinToString("\n")
                CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No threads found." })))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
            }
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
            val sessionName = request.stringArg("sessionName")
            val threadName = request.stringArg("threadName")

            try {
                val frames = apiWrapper.getStackTrace(sessionName, threadName)
                val resultText = frames
                    .mapIndexed { index, frame -> apiWrapper.formatStackFrame(frame, index) }
                    .joinToString("\n")
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
            val sessionName = request.stringArg("sessionName")
            val threadName = request.stringArg("threadName")
            val frameIndex = request.intArg("frameIndex")

            try {
                val variables = apiWrapper.getVariables(sessionName, threadName, frameIndex)
                val resultText = variables.joinToString("\n\n") { it.toDisplayString() }
                CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No variables found." })))
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error retrieving variables: ${e.message}")), isError = true)
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
            val sessionName = request.stringArg("sessionName")
            val threadName = request.stringArg("threadName")
            val frameIndex = request.intArg("frameIndex")
            val expression = request.stringArg("expression")

            try {
                val result = apiWrapper.evaluateExpression(sessionName, threadName, frameIndex, expression)
                CallToolResult(content = listOf(TextContent(result.toDisplayString())), isError = result.errorMessage != null)
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Evaluation error: ${e.message}")), isError = true)
            }
        }
    }

    private fun registerGetSourceContext() {
        server.addTool(
            name = "get_source_context",
            description = "Get source context for a specific stack frame",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("sessionName", buildJsonObject { put("type", "string") })
                    put("threadName", buildJsonObject { put("type", "string") })
                    put("frameIndex", buildJsonObject { put("type", "integer") })
                    put("contextLines", buildJsonObject { put("type", "integer") })
                },
                required = listOf("sessionName", "threadName", "frameIndex")
            )
        ) { request ->
            val sessionName = request.stringArg("sessionName")
            val threadName = request.stringArg("threadName")
            val frameIndex = request.intArg("frameIndex")
            val contextLines = request.intArg("contextLines", 3)

            try {
                val sourceContext = apiWrapper.getSourceContext(sessionName, threadName, frameIndex, contextLines)
                CallToolResult(
                    content = listOf(TextContent(sourceContext?.toDisplayString() ?: "Source context not available.")),
                    isError = sourceContext == null
                )
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Error retrieving source context: ${e.message}")), isError = true)
            }
        }
    }

    private fun registerListBreakpoints() {
        server.addTool(
            name = "list_breakpoints",
            description = "List all breakpoints in the project",
            inputSchema = ToolSchema(properties = buildJsonObject {})
        ) { _ ->
            val resultText = apiWrapper.getBreakpoints()
                .joinToString("\n") { apiWrapper.formatBreakpoint(it) }
            CallToolResult(content = listOf(TextContent(resultText.ifEmpty { "No breakpoints found." })))
        }
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.stringArg(name: String): String {
        return arguments?.get(name)?.jsonText().orEmpty()
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest.intArg(name: String, defaultValue: Int = 0): Int {
        return arguments?.get(name)?.jsonText()?.toIntOrNull() ?: defaultValue
    }

    private fun JsonElement.jsonText(): String {
        return toString().removeSurrounding("\"")
    }
}
