package com.github.joezzhu.debugmcp

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.components.service

class McpServerStartupActivity : ProjectActivity {

    companion object {
        private val LOG = logger<McpServerStartupActivity>()
    }

    override suspend fun execute(project: Project) {
        LOG.info("[DebugMCP] Starting MCP Server for project: ${project.name}")
        try {
            project.service<McpServerService>().start()
        } catch (e: Throwable) {
            LOG.error("[DebugMCP] Failed to start MCP Server", e)
        }
    }
}
