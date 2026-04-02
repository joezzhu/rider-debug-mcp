# Debugger MCP Server for Rider

一个面向 **JetBrains Rider** 的本地调试 MCP 插件。插件安装到 Rider 后，会在项目打开时自动启动一个本地 MCP Server，把 Rider 当前调试会话中的部分只读调试能力暴露给 AI / MCP 客户端。

当前实现重点是 **只读调试查询**：

- 列出活动调试会话
- 列出线程
- 查询调用栈
- 查询当前栈帧变量
- 查看断点列表
- 在当前栈帧上下文中求值表达式

## 功能概览

- **运行位置**：作为 Rider 插件运行在 IDE 内部
- **启动方式**：项目打开后自动启动
- **监听地址**：`127.0.0.1`
- **默认端口**：`29190`
- **MCP 端点**：`http://127.0.0.1:29190/mcp`
- **传输方式**：Streamable HTTP
- **安全边界**：仅监听本机回环地址，不对外网暴露

## 环境要求

- **Rider**：建议使用和构建目标接近的版本（当前工程目标为 `2024.1.4`）
- **JDK**：`17`
- **Gradle JVM**：建议设置为 `JDK 17`

## 构建插件

### 在 IntelliJ IDEA 中构建

1. 用 `IntelliJ IDEA` 打开本项目。
2. 确认 `Gradle JVM` 为 `JDK 17`。
3. 在右侧 `Gradle` 工具窗口中找到：
   - `Tasks` → `build` → `buildPlugin`
4. 双击 `buildPlugin`。

### 命令行构建

在项目根目录执行：

```powershell
.\gradlew.bat buildPlugin
```

构建成功后，插件 ZIP 包通常位于：

```text
build/distributions/
```

例如：

```text
build/distributions/rider-debug-mcp-plugin-1.0-SNAPSHOT.zip
```

## 安装到 Rider

1. 打开 `Rider`
2. 进入 `File` → `Settings`
3. 打开 `Plugins`
4. 点击右上角齿轮按钮
5. 选择 `Install Plugin from Disk...`
6. 选择 `build/distributions/` 下生成的 ZIP 包
7. 安装后重启 Rider

## 安装后怎么使用

这个插件 **没有单独的工具窗口或按钮**。它的使用方式是：

1. 在 Rider 中安装插件并重启
2. 打开一个项目
3. 插件会在项目启动后自动启动本地 MCP Server
4. 使用支持 **HTTP MCP / Streamable HTTP** 的 AI 客户端连接到：

```text
http://127.0.0.1:29190/mcp
```

5. 在 Rider 中启动调试，并让程序停在断点上
6. 通过 MCP 客户端调用本插件暴露的调试工具

### 重要说明

- **只有当 Rider 已打开项目时，插件才会启动**
- **只有当存在调试会话，且线程处于挂起/断点状态时，调用栈和变量信息才可用**
- 插件当前只提供 **只读查询能力**，不做继续执行、单步、修改变量等控制操作

## 连接 MCP 客户端

将你的 MCP 客户端指向下面这个地址：

```text
http://127.0.0.1:29190/mcp
```

不同客户端的配置方式不同，但核心都是：

- **传输类型**：HTTP / Streamable HTTP
- **服务地址**：`http://127.0.0.1:29190/mcp`

如果某个客户端要求填写“服务器 URL”、“endpoint”或“base URL”，请优先填写完整端点 `http://127.0.0.1:29190/mcp`。

## 典型使用流程

### 1. 启动 Rider 并打开项目

插件会在项目打开后自动启动。

### 2. 启动调试

在 Rider 里启动你的应用调试，并让程序停在断点上。

### 3. 通过 MCP 客户端查询调试信息

建议的调用顺序：

1. `list_debug_sessions`
2. `list_threads`
3. `get_stack_trace`
4. `get_variables`
5. `evaluate_expression`
6. `list_breakpoints`

## 当前提供的 MCP Tools

### `list_debug_sessions`
列出当前活动的调试会话。

**用途**：先确认当前有哪些可用的调试会话。

### `list_threads`
列出指定调试会话中的线程。

**输入参数**：

- `sessionName`: 调试会话名称

### `get_stack_trace`
获取指定调试会话、指定线程的调用栈。

**输入参数**：

- `sessionName`: 调试会话名称
- `threadName`: 线程名称

### `get_variables`
获取指定线程、指定栈帧中的可见变量。

**输入参数**：

- `sessionName`: 调试会话名称
- `threadName`: 线程名称
- `frameIndex`: 栈帧索引，通常从 `0` 开始

### `evaluate_expression`
在指定线程、指定栈帧的上下文里求值表达式。

**输入参数**：

- `sessionName`: 调试会话名称
- `threadName`: 线程名称
- `frameIndex`: 栈帧索引
- `expression`: 要求值的表达式

### `list_breakpoints`
列出当前项目中已有的断点。

## 示例调用顺序

假设 AI 已经连上 MCP Server：

1. 调用 `list_debug_sessions`
2. 从返回结果中拿到某个 `sessionName`
3. 调用 `list_threads(sessionName)`
4. 从返回结果中拿到某个 `threadName`
5. 调用 `get_stack_trace(sessionName, threadName)`
6. 选择某个 `frameIndex`
7. 调用 `get_variables(sessionName, threadName, frameIndex)`
8. 必要时调用 `evaluate_expression(sessionName, threadName, frameIndex, expression)`

## 如何确认插件已启动

可以通过以下方式确认：

- Rider 打开项目后没有插件加载错误
- MCP 客户端可以连接 `http://127.0.0.1:29190/mcp`
- Rider 日志中能看到类似 “MCP Server started successfully on port 29190” 的日志

查看日志的简单方式：

- 在 Rider 中使用 `Help` → `Show Log in Explorer`

## 已知限制

当前版本是最小可用实现，存在以下限制：

- **无图形界面**：没有设置页、状态栏入口或工具窗口
- **端口固定**：当前默认固定使用 `29190`
- **仅本机访问**：只监听 `127.0.0.1`
- **只读能力为主**：不提供继续执行、暂停、单步等控制能力
- **变量展示较基础**：当前变量输出格式仍比较原始
- **表达式求值结果较简略**：当前更偏向验证“可求值”，尚未对求值结果做完整格式化
- **浏览器型 Inspector 兼容性未完善**：当前未显式配置 CORS，更适合桌面端 / 非浏览器 MCP 客户端

## 开发调试

如果你是在开发插件本身，而不是安装给日常使用的 Rider，可以使用：

```powershell
.\gradlew.bat runIde
```

它会启动一个用于调试插件的沙箱 IDE 实例。

## 项目信息

- **Plugin ID**: `com.github.joezzhu.debugmcp`
- **Plugin Name**: `Debugger MCP Server`
- **Target Platform**: `Rider 2024.1.4`

## 后续可扩展方向

- 增加端口与开关配置页
- 增加更好的变量格式化输出
- 返回更完整的表达式求值结果
- 增加源码上下文查询
- 增加调试会话状态摘要
- 增加更严格的访问控制与鉴权
