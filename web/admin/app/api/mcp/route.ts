import { NextRequest, NextResponse } from "next/server";
import {
  RinbamMcpAuthError,
  RinbamMcpJsonRpcError,
  RinbamMcpInputError,
  RinbamMcpRateLimitError,
  RinbamMcpToolNotFoundError,
  checkRinbamMcpRateLimit,
  handleRinbamMcpJsonRpcRequest,
  isRinbamMcpJsonRpcRequest,
  isRinbamMcpLegacyRestEnabled,
  isRinbamMcpLegacyRestRequest,
  isRinbamMcpEnabled,
  parseRinbamMcpJsonRpcRequest,
  RINBAM_MCP_PROTOCOL_VERSION,
  requireRinbamMcpUser,
  rinbamMcpTools,
  callRinbamMcpTool,
  isSupportedMcpProtocolVersion,
  searchRinbamLinks,
  supportedMcpProtocolVersions,
  validateRinbamMcpToolDescriptors,
} from "@/lib/rinbamMcp";
import { optionalEnv } from "@/lib/env";

function authHeaders(request: NextRequest) {
  const resource = new URL("/api/mcp", request.url).toString();
  return {
    "WWW-Authenticate": `Bearer realm="rinbam-mcp", resource="${resource}", scope="links:read"`,
  };
}

function jsonError(message: string, status: number, request: NextRequest, extraHeaders?: Record<string, string>) {
  const headers = {
    "Cache-Control": "no-store",
    ...(status === 401 ? authHeaders(request) : {}),
    ...(extraHeaders ?? {}),
  };
  return NextResponse.json({ error: message }, { status, headers });
}

function jsonRpcError(
  id: string | number | null,
  code: number,
  message: string,
  status = 400,
  data?: Record<string, unknown>,
) {
  return NextResponse.json(
    { jsonrpc: "2.0", id, error: { code, message, ...(data ? { data } : {}) } },
    {
      status,
      headers: {
        "Cache-Control": "no-store",
        "MCP-Protocol-Version": RINBAM_MCP_PROTOCOL_VERSION,
      },
    },
  );
}

class RinbamMcpTransportError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = "RinbamMcpTransportError";
  }
}

function validateOrigin(request: NextRequest) {
  const origin = request.headers.get("origin");
  if (!origin) return;
  const requestOrigin = new URL(request.url).origin;
  const configured = (optionalEnv("URLSAVER_MCP_ALLOWED_ORIGINS") ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
  if (origin !== requestOrigin && !configured.includes(origin)) {
    throw new RinbamMcpTransportError(403, "origin_not_allowed");
  }
}

function validateTransportHeaders(request: NextRequest) {
  const contentType = request.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.startsWith("application/json")) {
    throw new RinbamMcpTransportError(415, "content_type_must_be_application_json");
  }
  const accept = request.headers.get("accept") ?? "";
  if (!accept.includes("application/json") || !accept.includes("text/event-stream")) {
    throw new RinbamMcpTransportError(406, "accept_must_include_json_and_event_stream");
  }
  const protocolVersion = request.headers.get("mcp-protocol-version");
  if (protocolVersion && !isSupportedMcpProtocolVersion(protocolVersion)) {
    throw new RinbamMcpTransportError(400, "unsupported_mcp_protocol_version");
  }
}

export async function GET(request: NextRequest) {
  if (!isRinbamMcpEnabled()) {
    return jsonError("mcp_disabled", 404, request);
  }
  validateOrigin(request);
  return new Response(null, {
    status: 405,
    headers: {
      Allow: "POST",
      "Cache-Control": "no-store",
    },
  });
}

export async function POST(request: NextRequest) {
  try {
    if (!isRinbamMcpEnabled()) {
      return jsonError("mcp_disabled", 404, request);
    }
    validateOrigin(request);
    const ctx = await requireRinbamMcpUser(request.headers.get("authorization"));
    checkRinbamMcpRateLimit(ctx);
    validateTransportHeaders(request);
    validateRinbamMcpToolDescriptors();

    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return jsonRpcError(null, -32700, "Parse error");
    }

    if (isRinbamMcpLegacyRestRequest(body)) {
      if (!isRinbamMcpLegacyRestEnabled()) {
        return jsonError("legacy_rest_disabled", 400, request, {
          "X-Rinbam-MCP-Transport": "legacy-rest-disabled",
        });
      }
      try {
        return NextResponse.json(await callRinbamMcpTool(ctx, body.tool, body.args ?? {}), {
          headers: {
            "Cache-Control": "no-store",
            "X-Rinbam-MCP-Transport": "legacy-rest",
          },
        });
      } catch (error) {
        if (error instanceof RinbamMcpToolNotFoundError || error instanceof RinbamMcpInputError) {
          return jsonError(error instanceof RinbamMcpToolNotFoundError ? "unknown_tool" : error.message, 400, request);
        }
        throw error;
      }
    }

    if (!isRinbamMcpJsonRpcRequest(body)) {
      return jsonRpcError(null, -32600, "Invalid Request");
    }

    try {
      const rpcRequest = parseRinbamMcpJsonRpcRequest(body);
      const response = await handleRinbamMcpJsonRpcRequest(rpcRequest, ctx);
      if (response === null) {
        return new Response(null, {
          status: 202,
          headers: { "Cache-Control": "no-store" },
        });
      }
      return NextResponse.json(response, {
        headers: {
          "Cache-Control": "no-store",
          "MCP-Protocol-Version": RINBAM_MCP_PROTOCOL_VERSION,
        },
      });
    } catch (error) {
      const id = typeof body.id === "string" || typeof body.id === "number" || body.id === null ? body.id : null;
      if (error instanceof RinbamMcpJsonRpcError) {
        return jsonRpcError(id, error.code, error.message, 400, error.data);
      }
      throw error;
    }
  } catch (error) {
    if (error instanceof RinbamMcpTransportError) {
      return jsonError(error.message, error.status, request);
    }
    if (error instanceof RinbamMcpAuthError) {
      return jsonError(error.message, 401, request);
    }
    if (error instanceof RinbamMcpInputError) {
      return jsonError(error.message, 400, request);
    }
    if (error instanceof RinbamMcpRateLimitError) {
      return jsonError(error.message, 429, request);
    }
    return jsonRpcError(null, -32603, "Internal MCP error", 500);
  }
}
