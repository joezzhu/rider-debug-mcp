package com.github.joezzhu.debugmcp

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.components.service

class McpServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 在项目启动后，获取 McpServerService 并启动
        project.service<McpServerService>().start()
    }
}
