export const MAX_ADMIN_JSON_BODY_BYTES = 64 * 1024;
export const MAX_RESEND_WEBHOOK_BODY_BYTES = 64 * 1024;

export class RequestBodyError extends Error {
  public readonly status: 400 | 413;
  public readonly code: "invalid_json_body" | "json_body_object_required" | "request_body_too_large";

  constructor(
    status: 400 | 413,
    code: "invalid_json_body" | "json_body_object_required" | "request_body_too_large",
  ) {
    super(code);
    this.name = "RequestBodyError";
    this.status = status;
    this.code = code;
  }
}

export async function readRequestBodyText(request: Request, maxBytes: number): Promise<string> {
  const declaredLength = Number(request.headers.get("content-length") ?? "");
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new RequestBodyError(413, "request_body_too_large");
  }

  if (!request.body) return "";

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      totalBytes += value.byteLength;
      if (totalBytes > maxBytes) {
        await reader.cancel("request_body_too_large");
        throw new RequestBodyError(413, "request_body_too_large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(totalBytes);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

export async function parseJsonObject(
  request: Request,
  maxBytes = MAX_ADMIN_JSON_BODY_BYTES,
): Promise<Record<string, unknown>> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(await readRequestBodyText(request, maxBytes));
  } catch (error) {
    if (error instanceof RequestBodyError) throw error;
    throw new RequestBodyError(400, "invalid_json_body");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new RequestBodyError(400, "json_body_object_required");
  }
  return parsed as Record<string, unknown>;
}
