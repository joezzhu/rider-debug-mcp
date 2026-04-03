#!/usr/bin/env python3
"""
DebugMCP Server 接口测试脚本
测试所有 8 个 MCP 工具接口的功能

使用方法:
    1. 在 Rider 中启动插件（打开一个带调试会话的项目）
    2. 运行: python test_mcp_server.py
    3. 可选参数:
        --url           MCP Server 地址 (默认 http://127.0.0.1:29190/mcp)
        --session       调试会话名称 (默认自动发现)
        --thread        线程名称 (默认自动发现)
        --frame-index   栈帧索引 (默认 0)
        --expression    要求值的表达式 (默认 "1+1")
        --context-lines 源码上下文行数 (默认 3)
        --verbose       显示完整请求/响应 JSON
"""

import argparse
import json
import sys
import time
import requests
from dataclasses import dataclass, field
from typing import Any, Optional

# ============================================================
# 配置
# ============================================================

DEFAULT_URL = "http://127.0.0.1:29190/mcp"
HEADERS = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}


# ============================================================
# 工具定义
# ============================================================

@dataclass
class TestResult:
    tool_name: str
    success: bool
    response: Optional[dict] = None
    error: Optional[str] = None
    elapsed_ms: float = 0.0
    skipped: bool = False
    skip_reason: str = ""


class McpClient:
    """简单的 MCP JSON-RPC 客户端"""

    def __init__(self, url: str, verbose: bool = False):
        self.url = url
        self.verbose = verbose
        self._request_id = 0
        self.session = requests.Session()
        self._mcp_session_id: Optional[str] = None

    def _next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    def _send(self, method: str, params: dict = None) -> dict:
        """发送 JSON-RPC 请求并返回解析后的响应"""
        payload = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": method,
            "params": params or {}
        }

        headers = dict(HEADERS)
        if self._mcp_session_id:
            headers["Mcp-Session-Id"] = self._mcp_session_id

        if self.verbose:
            print(f"\n  >>> REQUEST: {json.dumps(payload, indent=2, ensure_ascii=False)}")

        start = time.perf_counter()
        resp = self.session.post(self.url, json=payload, headers=headers, timeout=30)
        elapsed = (time.perf_counter() - start) * 1000

        # 保存 session id
        if "mcp-session-id" in resp.headers:
            self._mcp_session_id = resp.headers["mcp-session-id"]

        # 处理 SSE 格式的响应
        content_type = resp.headers.get("content-type", "")
        if "text/event-stream" in content_type:
            result = self._parse_sse(resp.text)
        else:
            result = resp.json()

        if self.verbose:
            print(f"  <<< RESPONSE ({elapsed:.0f}ms): {json.dumps(result, indent=2, ensure_ascii=False)}")

        return result

    def _parse_sse(self, text: str) -> dict:
        """从 SSE 流中解析最后一个 JSON-RPC 响应"""
        last_data = None
        for line in text.splitlines():
            if line.startswith("data:"):
                data_str = line[len("data:"):].strip()
                if data_str:
                    try:
                        last_data = json.loads(data_str)
                    except json.JSONDecodeError:
                        pass
        return last_data or {"error": "Failed to parse SSE response"}

    def initialize(self) -> dict:
        """初始化 MCP 连接"""
        return self._send("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "debug-mcp-test", "version": "1.0.0"}
        })

    def send_initialized(self):
        """发送 initialized 通知"""
        payload = {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {}
        }
        headers = dict(HEADERS)
        if self._mcp_session_id:
            headers["Mcp-Session-Id"] = self._mcp_session_id
        self.session.post(self.url, json=payload, headers=headers, timeout=10)

    def list_tools(self) -> dict:
        """列出所有注册的工具"""
        return self._send("tools/list")

    def call_tool(self, name: str, arguments: dict = None) -> dict:
        """调用指定工具"""
        return self._send("tools/call", {
            "name": name,
            "arguments": arguments or {}
        })


# ============================================================
# 测试用例
# ============================================================

class McpServerTester:
    def __init__(self, client: McpClient, args: argparse.Namespace):
        self.client = client
        self.args = args
        self.results: list[TestResult] = []
        self._session_name: Optional[str] = None
        self._thread_name: Optional[str] = None

    def run_all(self):
        """运行全部测试"""
        print("=" * 60)
        print("  DebugMCP Server 接口测试")
        print(f"  URL: {self.client.url}")
        print("=" * 60)

        # Step 0: 连接与初始化
        self._test_initialize()

        # Step 1: 列出工具
        self._test_list_tools()

        # Step 2: 无参数接口
        self._test_list_debug_sessions()
        self._test_list_breakpoints()

        # Step 3: 需要 sessionName 的接口
        self._test_get_debug_session_status()
        self._test_list_threads()

        # Step 4: 需要 sessionName + threadName 的接口
        self._test_get_stack_trace()
        self._test_get_variables()
        self._test_get_source_context()
        self._test_evaluate_expression()

        # 打印汇总
        self._print_summary()

    def _record(self, tool_name: str, response: dict, elapsed_ms: float = 0.0) -> TestResult:
        """记录测试结果"""
        is_error = False
        error_msg = None

        if "error" in response:
            is_error = True
            error_msg = response["error"].get("message", str(response["error"]))
        elif "result" in response:
            result = response["result"]
            if isinstance(result, dict) and result.get("isError"):
                is_error = True
                content = result.get("content", [])
                if content:
                    error_msg = content[0].get("text", "Unknown error")

        tr = TestResult(
            tool_name=tool_name,
            success=not is_error,
            response=response,
            error=error_msg,
            elapsed_ms=elapsed_ms
        )
        self.results.append(tr)
        return tr

    def _record_skip(self, tool_name: str, reason: str) -> TestResult:
        tr = TestResult(tool_name=tool_name, success=False, skipped=True, skip_reason=reason)
        self.results.append(tr)
        return tr

    def _get_content_text(self, response: dict) -> str:
        """从响应中提取文本内容"""
        result = response.get("result", {})
        content = result.get("content", [])
        if content and isinstance(content, list):
            return content[0].get("text", "")
        return ""

    def _print_result(self, tr: TestResult):
        """打印单个测试结果"""
        if tr.skipped:
            icon = "⏭️"
            status = f"SKIPPED ({tr.skip_reason})"
        elif tr.success:
            icon = "✅"
            status = "PASS"
        else:
            icon = "❌"
            status = f"FAIL: {tr.error}"

        time_str = f" ({tr.elapsed_ms:.0f}ms)" if tr.elapsed_ms > 0 else ""
        print(f"  {icon} {tr.tool_name}{time_str} - {status}")

        if tr.response and tr.success:
            text = self._get_content_text(tr.response)
            if text:
                # 截取前5行预览
                lines = text.strip().split("\n")
                preview = "\n".join(lines[:5])
                if len(lines) > 5:
                    preview += f"\n     ... ({len(lines) - 5} more lines)"
                for line in preview.split("\n"):
                    print(f"     {line}")

    # ----------------------------------------------------------
    # 各接口测试
    # ----------------------------------------------------------

    def _test_initialize(self):
        print("\n--- Initialize ---")
        try:
            start = time.perf_counter()
            resp = self.client.initialize()
            elapsed = (time.perf_counter() - start) * 1000

            has_error = "error" in resp
            tr = TestResult(
                tool_name="initialize",
                success=not has_error,
                response=resp,
                error=resp.get("error", {}).get("message") if has_error else None,
                elapsed_ms=elapsed
            )
            self.results.append(tr)

            if not has_error:
                server_info = resp.get("result", {}).get("serverInfo", {})
                print(f"  ✅ Connected to: {server_info.get('name', 'unknown')} v{server_info.get('version', '?')}")
                print(f"     Protocol: {resp.get('result', {}).get('protocolVersion', '?')}")
                # 发送 initialized 通知
                self.client.send_initialized()
            else:
                self._print_result(tr)
        except requests.ConnectionError:
            tr = TestResult(
                tool_name="initialize",
                success=False,
                error="无法连接到 MCP Server，请确认 Rider 插件已启动"
            )
            self.results.append(tr)
            self._print_result(tr)
            print("\n⛔ 无法连接，终止测试。")
            self._print_summary()
            sys.exit(1)

    def _test_list_tools(self):
        print("\n--- tools/list ---")
        start = time.perf_counter()
        resp = self.client.list_tools()
        elapsed = (time.perf_counter() - start) * 1000

        tr = TestResult(
            tool_name="tools/list",
            success="error" not in resp,
            response=resp,
            elapsed_ms=elapsed
        )
        self.results.append(tr)

        tools = resp.get("result", {}).get("tools", [])
        print(f"  ✅ 已注册 {len(tools)} 个工具:" if tr.success else f"  ❌ {resp}")
        for t in tools:
            required = t.get("inputSchema", {}).get("required", [])
            req_str = f" (required: {', '.join(required)})" if required else " (无参数)"
            print(f"     - {t['name']}{req_str}")

    def _test_list_debug_sessions(self):
        print("\n--- list_debug_sessions ---")
        start = time.perf_counter()
        resp = self.client.call_tool("list_debug_sessions")
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("list_debug_sessions", resp, elapsed)
        self._print_result(tr)

        # 自动发现 sessionName
        if tr.success and not self.args.session:
            text = self._get_content_text(resp)
            if text and "No active debug sessions" not in text:
                # 尝试解析 "session: xxx"
                for line in text.split("\n"):
                    if line.startswith("session:"):
                        self._session_name = line.split(":", 1)[1].strip()
                        print(f"     📌 自动发现会话: {self._session_name}")
                        break
        elif self.args.session:
            self._session_name = self.args.session

    def _test_list_breakpoints(self):
        print("\n--- list_breakpoints ---")
        start = time.perf_counter()
        resp = self.client.call_tool("list_breakpoints")
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("list_breakpoints", resp, elapsed)
        self._print_result(tr)

    def _test_get_debug_session_status(self):
        print("\n--- get_debug_session_status ---")
        if not self._session_name:
            self._print_result(self._record_skip("get_debug_session_status", "无活跃调试会话"))
            return

        start = time.perf_counter()
        resp = self.client.call_tool("get_debug_session_status", {
            "sessionName": self._session_name
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("get_debug_session_status", resp, elapsed)
        self._print_result(tr)

    def _test_list_threads(self):
        print("\n--- list_threads ---")
        if not self._session_name:
            self._print_result(self._record_skip("list_threads", "无活跃调试会话"))
            return

        start = time.perf_counter()
        resp = self.client.call_tool("list_threads", {
            "sessionName": self._session_name
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("list_threads", resp, elapsed)
        self._print_result(tr)

        # 自动发现 threadName
        if tr.success and not self.args.thread:
            text = self._get_content_text(resp)
            if text and "No threads found" not in text:
                # 格式: [0] ThreadName
                for line in text.split("\n"):
                    line = line.strip()
                    if line.startswith("[0]"):
                        self._thread_name = line[3:].strip()
                        print(f"     📌 自动发现线程: {self._thread_name}")
                        break
        elif self.args.thread:
            self._thread_name = self.args.thread

    def _test_get_stack_trace(self):
        print("\n--- get_stack_trace ---")
        if not self._session_name or not self._thread_name:
            self._print_result(self._record_skip("get_stack_trace", "缺少会话或线程信息"))
            return

        start = time.perf_counter()
        resp = self.client.call_tool("get_stack_trace", {
            "sessionName": self._session_name,
            "threadName": self._thread_name
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("get_stack_trace", resp, elapsed)
        self._print_result(tr)

    def _test_get_variables(self):
        print("\n--- get_variables ---")
        if not self._session_name or not self._thread_name:
            self._print_result(self._record_skip("get_variables", "缺少会话或线程信息"))
            return

        frame_index = self.args.frame_index
        start = time.perf_counter()
        resp = self.client.call_tool("get_variables", {
            "sessionName": self._session_name,
            "threadName": self._thread_name,
            "frameIndex": frame_index
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("get_variables", resp, elapsed)
        self._print_result(tr)

    def _test_get_source_context(self):
        print("\n--- get_source_context ---")
        if not self._session_name or not self._thread_name:
            self._print_result(self._record_skip("get_source_context", "缺少会话或线程信息"))
            return

        frame_index = self.args.frame_index
        context_lines = self.args.context_lines
        start = time.perf_counter()
        resp = self.client.call_tool("get_source_context", {
            "sessionName": self._session_name,
            "threadName": self._thread_name,
            "frameIndex": frame_index,
            "contextLines": context_lines
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("get_source_context", resp, elapsed)
        self._print_result(tr)

    def _test_evaluate_expression(self):
        print("\n--- evaluate_expression ---")
        if not self._session_name or not self._thread_name:
            self._print_result(self._record_skip("evaluate_expression", "缺少会话或线程信息"))
            return

        frame_index = self.args.frame_index
        expression = self.args.expression
        start = time.perf_counter()
        resp = self.client.call_tool("evaluate_expression", {
            "sessionName": self._session_name,
            "threadName": self._thread_name,
            "frameIndex": frame_index,
            "expression": expression
        })
        elapsed = (time.perf_counter() - start) * 1000

        tr = self._record("evaluate_expression", resp, elapsed)
        self._print_result(tr)

    # ----------------------------------------------------------
    # 汇总
    # ----------------------------------------------------------

    def _print_summary(self):
        print("\n" + "=" * 60)
        print("  测试结果汇总")
        print("=" * 60)

        total = len(self.results)
        passed = sum(1 for r in self.results if r.success)
        failed = sum(1 for r in self.results if not r.success and not r.skipped)
        skipped = sum(1 for r in self.results if r.skipped)

        print(f"  总计: {total}  |  通过: {passed}  |  失败: {failed}  |  跳过: {skipped}")

        if failed > 0:
            print("\n  失败项:")
            for r in self.results:
                if not r.success and not r.skipped:
                    print(f"    ❌ {r.tool_name}: {r.error}")

        if skipped > 0:
            print("\n  跳过项:")
            for r in self.results:
                if r.skipped:
                    print(f"    ⏭️  {r.tool_name}: {r.skip_reason}")

        total_time = sum(r.elapsed_ms for r in self.results)
        print(f"\n  总耗时: {total_time:.0f}ms")
        print("=" * 60)


# ============================================================
# 入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="DebugMCP Server 接口测试")
    parser.add_argument("--url", default=DEFAULT_URL, help=f"MCP Server 地址 (默认: {DEFAULT_URL})")
    parser.add_argument("--session", default=None, help="指定调试会话名称 (默认自动发现)")
    parser.add_argument("--thread", default=None, help="指定线程名称 (默认自动发现)")
    parser.add_argument("--frame-index", type=int, default=0, help="栈帧索引 (默认: 0)")
    parser.add_argument("--expression", default="1+1", help='求值表达式 (默认: "1+1")')
    parser.add_argument("--context-lines", type=int, default=3, help="源码上下文行数 (默认: 3)")
    parser.add_argument("--verbose", "-v", action="store_true", help="显示完整请求/响应 JSON")
    args = parser.parse_args()

    client = McpClient(url=args.url, verbose=args.verbose)
    tester = McpServerTester(client, args)
    tester.run_all()


if __name__ == "__main__":
    main()
