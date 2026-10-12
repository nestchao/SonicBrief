"""End-to-end stdio MCP handshake against the portable Node launcher.

This script is executed by CI *outside* the frozen runtime.  A Python MCP
client only stands in for an AI application; the MCP server itself remains a
self-contained Windows executable launched by Node.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


ROOT = Path(__file__).resolve().parents[1]
LAUNCHER = ROOT / "npm" / "sonicbrief-mcp" / "bin" / "sonicbrief-mcp.mjs"


async def verify() -> None:
    runtime = os.environ.get("SONICBRIEF_MCP_BINARY")
    if not runtime or not Path(runtime).is_file():
        raise RuntimeError("Set SONICBRIEF_MCP_BINARY to the packaged SonicBrief-MCP.exe.")

    params = StdioServerParameters(
        command="node",
        args=[str(LAUNCHER)],
        env={**os.environ, "SONICBRIEF_FORCE_CPU": "1"},
    )
    async with stdio_client(params) as (reader, writer):
        async with ClientSession(reader, writer) as session:
            await asyncio.wait_for(session.initialize(), timeout=45)
            tools = await asyncio.wait_for(session.list_tools(), timeout=20)
            names = {tool.name for tool in tools.tools}
            expected = {"get_health", "submit_media_url", "get_job", "get_transcript", "export_transcript"}
            missing = expected - names
            if missing:
                raise AssertionError(f"Portable MCP tools not registered: {sorted(missing)}")
            result = await asyncio.wait_for(session.call_tool("get_health", arguments={}), timeout=75)
            if result.isError:
                raise AssertionError(f"Portable MCP health tool returned error: {result}")
            payload = result.structuredContent
            if not isinstance(payload, dict):
                # Newer MCP implementations can return a nested result wrapper.
                raise AssertionError(f"Unexpected MCP health structure: {result}")
            health = payload.get("result", payload)
            if not isinstance(health, dict) or not health.get("backend_reachable"):
                raise AssertionError(f"Portable MCP backend not healthy: {health}")
            if health.get("backend_runtime") != "native":
                raise AssertionError(f"Expected Windows native backend, got {health}")
            print("Portable MCP stdio handshake and get_health passed.")


if __name__ == "__main__":
    if sys.platform != "win32":
        raise RuntimeError("Run on Windows for the portable Windows CI build.")
    asyncio.run(verify())
