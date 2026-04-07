# Debugger MCP Server for Rider

一个面向 **JetBrains Rider** 的本地调试 MCP 插件。插件安装到 Rider 后，会在项目打开时自动启动一个本地 MCP Server，把 Rider 当前调试会话中的部分只读调试能力暴露给 AI / MCP 客户端。

当前实现重点是 **只读调试查询**：

- 列出活动调试会话
- 获取调试会话状态摘要
- 列出线程
- 查询调用栈（含函数签名）
- 查询当前栈帧变量（紧凑格式：`name : type = value [+]`）
- 在当前栈帧上下文中求值表达式，失败时自动展开子成员
- 查询当前栈帧对应的源码上下文
- 查看断点列表


## 功能概览

- **运行位置**：作为 Rider 插件运行在 IDE 内部
- **启动方式**：项目打开后自动启动
- **监听地址**：`127.0.0.1`
- **默认端口**：`29190`
- **MCP 端点**：`http://127.0.0.1:29190/mcp`
- **传输方式**：Streamable HTTP
- **安全边界**：仅监听本机回环地址，不对外网暴露

## 环境要求

- **Rider**：建议使用和构建目标接近的版本（当前工程目标为 `2025.3`）
- **JDK**：`21`
- **Gradle JVM**：建议设置为 `JDK 21`


## 构建插件

### 命令行构建（推荐）

```powershell
.\gradlew.bat fixPluginZip
```

`fixPluginZip` 会先 `buildPlugin`，再自动清理 Ktor 2.x/3.x 冲突、coroutines 等 IDE 冲突依赖，产出可直接安装的 ZIP。

构建产物位于：

```text
build/distributions/rider-debug-mcp-plugin-<version>.zip
```

### 在 IntelliJ IDEA 中构建

1. 用 `IntelliJ IDEA` 打开本项目
2. 确认 `Gradle JVM` 为 `JDK 21`
3. 在右侧 `Gradle` 工具窗口中执行 `fixPluginZip`

## 安装到 Rider

1. 打开 `Rider`
2. 进入 `File` → `Settings` → `Plugins`
3. 点击右上角齿轮按钮 → `Install Plugin from Disk...`
4. 选择 `build/distributions/` 下生成的 ZIP 包
5. 安装后重启 Rider

## 安装后怎么使用

这个插件 **没有单独的工具窗口或按钮**。它的使用方式是：

1. 在 Rider 中安装插件并重启
2. 打开一个项目（插件自动启动 MCP Server）
3. 使用支持 **Streamable HTTP** 的 MCP 客户端连接到：

```text
http://127.0.0.1:29190/mcp
```

4. 在 Rider 中启动调试，并让程序停在断点上
5. 通过 MCP 客户端调用本插件暴露的调试工具

### MCP 客户端配置

```json
{
  "mcpServers": {
    "rider-debug-mcp": {
      "url": "http://127.0.0.1:29190/mcp"
    }
  }
}
```

### 重要说明

- **只有当 Rider 已打开项目时，插件才会启动**
- **只有当存在调试会话，且线程处于挂起/断点状态时，调用栈和变量信息才可用**
- 插件当前只提供 **只读查询能力**，不做继续执行、单步、修改变量等控制操作


## 当前提供的 MCP Tools

### `list_debug_sessions`
列出当前活动的调试会话。

### `get_debug_session_status`
获取指定调试会话的状态摘要（暂停状态、线程数、当前位置等）。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |

### `list_threads`
列出指定调试会话中的线程。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |

### `get_stack_trace`
获取指定线程的调用栈，包含函数签名和源码位置。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |
| `threadName` | string | 是 | 线程名称（支持模糊匹配） |

**线程名模糊匹配**：支持精确名、索引（`0`）、子串（`Main Thread`）、`list_threads` 输出格式（`[0] Thread-1-[Main Thread] (50124)`）。

### `get_variables`
获取指定栈帧中的可见变量。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |
| `threadName` | string | 是 | 线程名称 |
| `frameIndex` | integer | 是 | 栈帧索引（0=栈顶） |

**输出格式**：每行一个变量，格式 `name : type = value [+]`，其中 `[+]` 表示可展开（有子成员）。

### `evaluate_expression`
在指定栈帧上下文中求值表达式。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |
| `threadName` | string | 是 | 线程名称 |
| `frameIndex` | integer | 是 | 栈帧索引 |
| `expression` | string | 是 | 求值表达式 |

**特性**：
- 当结果有子成员（`[+]`）或值不可用时，自动展开返回所有子成员
- 值不可用时附带 HINT 提示（建议直接访问变量而非调用方法）
- frameIndex=0 且不可用时，额外提示检查帧索引

### `get_source_context`
获取指定栈帧对应的源码上下文。

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionName` | string | 是 | 调试会话名称 |
| `threadName` | string | 是 | 线程名称 |
| `frameIndex` | integer | 是 | 栈帧索引 |
| `contextLines` | integer | 否 | 上下文行数（默认 3） |

### `list_breakpoints`
列出当前项目中已有的断点。


## 示例调用顺序

```
1. list_debug_sessions          → 获取 sessionName
2. list_threads(sessionName)    → 获取 threadName
3. get_stack_trace(session, thread)  → 查看调用栈，选择 frameIndex
4. get_variables(session, thread, frameIndex)
5. get_source_context(session, thread, frameIndex)
6. evaluate_expression(session, thread, frameIndex, "expr")
```


## 如何确认插件已启动

- MCP 客户端可以连接 `http://127.0.0.1:29190/mcp`
- Rider 日志中能看到 "MCP Server started successfully on port 29190"
- 查看日志：`Help` → `Show Log in Explorer`

## 测试

项目自带 Python 测试脚本，可自动测试所有接口：

```powershell
pip install requests
python test_mcp_server.py          # 基本测试（自动发现会话/线程）
python test_mcp_server.py -v       # 详细模式（显示完整 JSON）
python test_mcp_server.py --help   # 查看所有参数
```

## 已知限制

- **无图形界面**：没有设置页、状态栏入口或工具窗口
- **端口固定**：当前默认固定使用 `29190`
- **仅本机访问**：只监听 `127.0.0.1`
- **只读能力为主**：不提供继续执行、暂停、单步、修改变量等控制能力
- **C++ 方法调用求值受限**：优化编译的 C++ 代码中，方法调用（如 `.Num()`）可能无法求值，建议直接访问成员变量
- **源码上下文依赖本地源码可用性**：如果当前栈帧无法定位到可读取文件，则无法返回源码片段
- **浏览器型 Inspector 兼容性未完善**：当前未显式配置 CORS，更适合桌面端 MCP 客户端


## 开发调试

```powershell
.\gradlew.bat runIde
```

启动沙箱 IDE 实例用于调试插件本身。

## 项目信息

- **Plugin ID**: `com.github.joezzhu.debugmcp`
- **Plugin Name**: `Debugger MCP Server`
- **Target Platform**: `Rider 2025.3`
- **Version**: `0.4.0`


## 后续可扩展方向

- 增加端口、自动启动开关与监听策略的配置页
- 为调试会话、线程和栈帧引入更稳定的标识，减少依赖名称与索引定位
- 支持变量深层递归展开与更结构化的 JSON 返回格式
- 增强源码上下文能力，返回更完整定位信息与不可读原因说明
- 在明确安全边界后，评估加入继续执行、暂停、单步等调试控制能力
- 增加更严格的访问控制、鉴权与审计日志

