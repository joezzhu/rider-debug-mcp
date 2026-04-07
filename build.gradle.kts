plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
}

group = "com.github.joezzhu"
version = "0.4.0"

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("253")
            untilBuild.set("253.*")
        }
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("253")
        untilBuild.set("253.*")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2025.3")
        instrumentationTools()
    }

    // MCP SDK — exclude ALL transitive Ktor deps, we provide Ktor 3.x ourselves
    implementation("io.modelcontextprotocol:kotlin-sdk:0.10.0") {
        exclude(group = "io.ktor")
    }

    // Ktor Server CIO (must match MCP SDK's Ktor 3.x)
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
    // Required by MCP SDK's mcpStreamableHttp
    implementation("io.ktor:ktor-server-sse:3.2.3")
    implementation("io.ktor:ktor-server-websockets:3.2.3")

}

// Force all ktor modules to 3.2.3 to resolve Ktor 2.x/3.x conflict from MCP SDK transitive deps
configurations.all {
    resolutionStrategy {
        force(
            "io.ktor:ktor-server-cio:3.2.3",
            "io.ktor:ktor-server-cio-jvm:3.2.3",
            "io.ktor:ktor-server-content-negotiation:3.2.3",
            "io.ktor:ktor-server-content-negotiation-jvm:3.2.3",
            "io.ktor:ktor-server-host-common:3.2.3",
            "io.ktor:ktor-server-host-common-jvm:3.2.3",
            "io.ktor:ktor-server-core:3.2.3",
            "io.ktor:ktor-server-core-jvm:3.2.3",
            "io.ktor:ktor-serialization-kotlinx-json:3.2.3",
            "io.ktor:ktor-serialization-kotlinx-json-jvm:3.2.3",
            "io.ktor:ktor-serialization-kotlinx:3.2.3",
            "io.ktor:ktor-serialization-kotlinx-jvm:3.2.3",
            "io.ktor:ktor-serialization:3.2.3",
            "io.ktor:ktor-serialization-jvm:3.2.3",
            "io.ktor:ktor-http:3.2.3",
            "io.ktor:ktor-http-jvm:3.2.3",
            "io.ktor:ktor-http-cio:3.2.3",
            "io.ktor:ktor-http-cio-jvm:3.2.3",
            "io.ktor:ktor-utils:3.2.3",
            "io.ktor:ktor-utils-jvm:3.2.3",
            "io.ktor:ktor-io:3.2.3",
            "io.ktor:ktor-io-jvm:3.2.3",
            "io.ktor:ktor-events:3.2.3",
            "io.ktor:ktor-events-jvm:3.2.3",
            "io.ktor:ktor-network:3.2.3",
            "io.ktor:ktor-network-jvm:3.2.3",
            "io.ktor:ktor-client-core:3.2.3",
            "io.ktor:ktor-client-core-jvm:3.2.3"
        )
    }
}

// Post-process: fix Ktor 2.x/3.x conflict in the built ZIP
// Usage: ./gradlew fixPluginZip
tasks.register("fixPluginZip") {
    dependsOn("buildPlugin")
    doLast {
        val distDir = file("build/distributions")
        distDir.listFiles()?.filter { it.extension == "zip" }?.forEach { zipFile ->
            val tmpDir = file("build/tmp/fix-ktor")
            tmpDir.deleteRecursively()
            copy { from(zipTree(zipFile)); into(tmpDir) }
            val pluginDir = tmpDir.listFiles()?.firstOrNull { it.isDirectory }
            val libDir = pluginDir?.resolve("lib")
            if (libDir != null && libDir.exists()) {
                // Delete non-3.2.3 Ktor JARs (MCP SDK pulls in Ktor 2.x transitively)
                libDir.listFiles()?.filter { it.name.startsWith("ktor-") && !it.name.contains("3.2.3") }
                    ?.forEach { println("[DebugMCP] Removing: ${it.name}"); it.delete() }
                // Delete IDE-provided libs (coroutines, slf4j, kotlin-stdlib conflict with bundled)
                libDir.listFiles()?.filter {
                    it.name.startsWith("kotlinx-coroutines-") || it.name.startsWith("slf4j-api-") ||
                    it.name.startsWith("kotlin-stdlib-") || it.name.startsWith("kotlin-reflect-")
                }?.forEach { println("[DebugMCP] Removing: ${it.name}"); it.delete() }
                // Copy missing Ktor 3.x JARs from resolved runtimeClasspath
                val neededModules = setOf(
                    "ktor-server-cio-jvm", "ktor-server-content-negotiation-jvm",
                    "ktor-serialization-kotlinx-json-jvm", "ktor-serialization-kotlinx-jvm",
                    "ktor-server-sse-jvm", "ktor-sse-jvm",
                    "ktor-server-websockets-jvm", "ktor-websocket-serialization-jvm",
                    "ktor-client-core-jvm"
                )
                configurations.named("runtimeClasspath").get().resolvedConfiguration.resolvedArtifacts.forEach { art ->
                    val id = art.moduleVersion.id
                    if (id.group == "io.ktor" && neededModules.contains(id.name) && id.version == "3.2.3") {
                        val dest = libDir.resolve(art.file.name)
                        if (!dest.exists()) {
                            println("[DebugMCP] Adding: ${art.file.name}")
                            art.file.copyTo(dest)
                        }
                    }
                }
            }
            // Repack ZIP
            zipFile.delete()
            ant.withGroovyBuilder {
                "zip"("destfile" to zipFile.absolutePath) {
                    "fileset"("dir" to tmpDir.absolutePath)
                }
            }
            tmpDir.deleteRecursively()
            println("[DebugMCP] Fixed ZIP: ${zipFile.name}")
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}
