plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
}

group = "com.github.joezzhu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        rider("2024.1.4")
        instrumentationTools()
    }
    
    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.10.0")
    
    // Ktor Server CIO
    implementation("io.ktor:ktor-server-cio:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
    
}

kotlin {
    jvmToolchain(17)
}