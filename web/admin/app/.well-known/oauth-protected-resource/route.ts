import { NextRequest, NextResponse } from "next/server";

export async function GET(request: NextRequest) {
  const resource = new URL("/api/mcp", request.url).toString();
  return NextResponse.json({
    resource,
    authorization_servers: [new URL("/", request.url).origin],
    scopes_supported: ["links:read"],
    resource_documentation: new URL("/docs/ai/mcp-auth", request.url).toString(),
  });
}
