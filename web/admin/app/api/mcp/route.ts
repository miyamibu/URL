import { NextRequest, NextResponse } from "next/server";
import {
  RinbamMcpAuthError,
  RinbamMcpInputError,
  RinbamMcpRateLimitError,
  checkRinbamMcpRateLimit,
  fetchRinbamLink,
  getAiReceipt,
  isRinbamMcpEnabled,
  listRecentSavedLinks,
  listRinbamTags,
  requireRinbamMcpUser,
  rinbamMcpTools,
  searchRinbamLinks,
  validateRinbamMcpToolDescriptors,
} from "@/lib/rinbamMcp";

function authHeaders(request: NextRequest) {
  const resource = new URL("/api/mcp", request.url).toString();
  return {
    "WWW-Authenticate": `Bearer realm="rinbam-mcp", resource="${resource}", scope="links:read"`,
  };
}

function jsonError(message: string, status: number, request: NextRequest) {
  const headers = status === 401 ? authHeaders(request) : undefined;
  return NextResponse.json({ error: message }, { status, headers });
}

export async function GET() {
  if (!isRinbamMcpEnabled()) {
    return NextResponse.json({ error: "mcp_disabled" }, { status: 404 });
  }
  validateRinbamMcpToolDescriptors();
  return NextResponse.json({
    name: "rinbam-readonly-links",
    version: "0.1.0",
    auth: "bearer_required",
    tools: rinbamMcpTools,
  });
}

export async function POST(request: NextRequest) {
  try {
    if (!isRinbamMcpEnabled()) {
      return jsonError("mcp_disabled", 404, request);
    }
    validateRinbamMcpToolDescriptors();
    const ctx = await requireRinbamMcpUser(request.headers.get("authorization"));
    checkRinbamMcpRateLimit(ctx);
    const body = (await request.json()) as { tool?: string; args?: Record<string, unknown> };
    const args = body.args ?? {};
    switch (body.tool) {
      case "search":
        return NextResponse.json(await searchRinbamLinks(ctx, args));
      case "fetch":
        return NextResponse.json(await fetchRinbamLink(ctx, args));
      case "rinbam.list_tags":
        return NextResponse.json(await listRinbamTags(ctx));
      case "rinbam.get_ai_receipt":
        return NextResponse.json(await getAiReceipt(args));
      case "rinbam.list_recent_saved_links":
        return NextResponse.json(await listRecentSavedLinks(ctx, args));
      default:
        return jsonError("unknown_tool", 400, request);
    }
  } catch (error) {
    if (error instanceof RinbamMcpAuthError) {
      return jsonError(error.message, 401, request);
    }
    if (error instanceof RinbamMcpInputError) {
      return jsonError(error.message, 400, request);
    }
    if (error instanceof RinbamMcpRateLimitError) {
      return jsonError(error.message, 429, request);
    }
    const message = error instanceof Error ? error.message : "mcp_request_failed";
    return jsonError(message, 500, request);
  }
}
