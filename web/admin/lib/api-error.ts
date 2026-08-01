import { NextResponse } from "next/server";
import { RequestBodyError } from "./request-body";

function safeErrorCode(error: unknown): string | null {
  if (!error || typeof error !== "object" || !("code" in error) || typeof error.code !== "string") return null;
  return /^[A-Za-z0-9_-]{1,64}$/.test(error.code) ? error.code : null;
}

export function adminApiError(error: unknown, publicMessage: string): Response {
  if (error instanceof Response) return error;
  if (error instanceof RequestBodyError) {
    return NextResponse.json(
      { error: error.code },
      { status: error.status, headers: { "Cache-Control": "no-store" } },
    );
  }
  const errorId = crypto.randomUUID();
  console.error("admin_api_error", {
    errorId,
    name: error instanceof Error ? error.name : "UnknownError",
    code: safeErrorCode(error),
  });
  return NextResponse.json(
    { error: publicMessage, errorId },
    { status: 500, headers: { "Cache-Control": "no-store" } },
  );
}
