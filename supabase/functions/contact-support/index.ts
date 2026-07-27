import postgres from "npm:postgres@3.4.5";
import {
  encryptContactSupportPayload,
  validateEncryptionSecret,
} from "../_shared/contact-support-outbox-crypto.ts";

type ContactSupportRequest = {
  email?: unknown;
  name?: unknown;
  message?: unknown;
  source?: unknown;
  idempotencyKey?: unknown;
  honeypot?: unknown;
  platform?: unknown;
  appVersion?: unknown;
  buildType?: unknown;
};

export type NormalizedContactRequest = {
  email: string;
  name: string;
  message: string;
  source: string;
  idempotencyKey: string;
  platform: string;
  appVersion: string;
  buildType: string;
};

type QueueResult = {
  ok: true;
  requestId: string;
} | {
  ok: false;
  status: number;
  error: string;
};

type OutboxPayload = {
  email: string;
  name: string;
  message: string;
  source: string;
  platform: string;
  appVersion: string;
  buildType: string;
  isSignedIn: boolean;
};

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type, idempotency-key",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const MAX_BODY_BYTES = 16 * 1024;
const MAX_MESSAGE_BYTES = 8 * 1024;
const MAX_FIELD_BYTES = 512;
const MAX_SOURCE_BYTES = 128;
const MAX_IDEMPOTENCY_KEY_BYTES = 128;
const MIN_IDEMPOTENCY_KEY_BYTES = 16;
const MIN_RATE_LIMIT_SECRET_BYTES = 32;
const MAX_RATE_LIMIT_SECRET_BYTES = 256;
const VALID_PLATFORMS = new Set(["android", "ios"]);
const VALID_BUILD_TYPES = new Set(["debug", "release"]);
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const IDEMPOTENCY_KEY_PATTERN = /^[A-Za-z0-9._~-]{16,128}$/;
const SOURCE_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;

export async function handleRequest(request: Request): Promise<Response> {
  const requestId = crypto.randomUUID();

  if (request.method === "OPTIONS") {
    return jsonResponse(null, 204);
  }
  if (request.method === "GET") {
    return await healthResponse();
  }
  if (request.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed", requestId }, 405);
  }

  try {
    const body = await readContactRequest(request);
    const normalized = normalizeContactRequest(
      body,
      request.headers.get("idempotency-key"),
    );

    if (typeof body.honeypot === "string" && body.honeypot.trim()) {
      return acceptedResponse(requestId);
    }

    const validationError = validateContactRequest(normalized);
    if (validationError) {
      return jsonResponse({ error: validationError, requestId }, 400);
    }

    const databaseUrl = requiredEnv("SUPABASE_DB_URL");
    const authenticatedUserId = await resolveAuthenticatedUser(request);
    const emailHash = await hashClient(normalized.email);
    const authUserIdHash = authenticatedUserId
      ? await hashClient(authenticatedUserId)
      : null;
    const ipHash = await hashClient(clientIp(request));
    const queued = await enqueueContactSupportRequest(
      databaseUrl,
      requestId,
      normalized,
      emailHash,
      authUserIdHash,
      ipHash,
    );
    if (!queued.ok) {
      return jsonResponse({ error: queued.error, requestId }, queued.status);
    }

    return acceptedResponse(queued.requestId);
  } catch (error) {
    if (error instanceof RequestTooLargeError) {
      return jsonResponse({ error: "payload_too_large", requestId }, 413);
    }
    if (error instanceof AuthenticationError) {
      return jsonResponse({ error: "authentication_required", requestId }, 401);
    }
    if (error instanceof ConfigurationError) {
      console.error(
        "contact-support configuration failure",
        safeErrorCode(error),
      );
      return jsonResponse(
        { error: "server_configuration_error", requestId },
        503,
      );
    }

    console.error("contact-support failed", safeErrorCode(error));
    return jsonResponse({ error: "server_error", requestId }, 502);
  }
}

export function acceptedResponse(requestId: string): Response {
  return jsonResponse({ status: "accepted", requestId }, 202);
}

export async function readContactRequest(
  request: Request,
): Promise<ContactSupportRequest> {
  const contentLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    throw new RequestTooLargeError();
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength > MAX_BODY_BYTES) {
    throw new RequestTooLargeError();
  }

  try {
    const body: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return body && typeof body === "object" && !Array.isArray(body)
      ? body as ContactSupportRequest
      : {};
  } catch {
    return {};
  }
}

export function normalizeContactRequest(
  body: ContactSupportRequest,
  idempotencyHeader: string | null = null,
): NormalizedContactRequest {
  const platform = stringValue(body.platform);
  return {
    email: stringValue(body.email).toLowerCase(),
    name: stringValue(body.name),
    message: stringValue(body.message),
    source: stringValue(body.source) ||
      (platform ? `mobile:${platform}` : "unknown"),
    idempotencyKey: stringValue(idempotencyHeader) ||
      stringValue(body.idempotencyKey),
    platform: platform || "unknown",
    appVersion: stringValue(body.appVersion) || "unknown",
    buildType: stringValue(body.buildType) || "unknown",
  };
}

export function validateContactRequest(
  body: NormalizedContactRequest,
): string | null {
  if (!body.email || !body.name || !body.message || !body.idempotencyKey) {
    return "missing_required_fields";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(body.email)) {
    return "invalid_email";
  }
  if (
    textByteLength(body.email) > MAX_FIELD_BYTES ||
    textByteLength(body.name) > MAX_FIELD_BYTES
  ) {
    return "field_too_long";
  }
  if (textByteLength(body.message) > MAX_MESSAGE_BYTES) {
    return "message_too_long";
  }
  if (
    textByteLength(body.source) > MAX_SOURCE_BYTES ||
    !SOURCE_PATTERN.test(body.source)
  ) {
    return "invalid_source";
  }
  if (
    textByteLength(body.idempotencyKey) < MIN_IDEMPOTENCY_KEY_BYTES ||
    textByteLength(body.idempotencyKey) > MAX_IDEMPOTENCY_KEY_BYTES ||
    !IDEMPOTENCY_KEY_PATTERN.test(body.idempotencyKey)
  ) {
    return "invalid_idempotency_key";
  }
  if (
    textByteLength(body.platform) > MAX_FIELD_BYTES ||
    !VALID_PLATFORMS.has(body.platform)
  ) {
    return "invalid_platform";
  }
  if (textByteLength(body.appVersion) > MAX_FIELD_BYTES) {
    return "field_too_long";
  }
  if (
    textByteLength(body.buildType) > MAX_FIELD_BYTES ||
    !VALID_BUILD_TYPES.has(body.buildType)
  ) {
    return "invalid_build_type";
  }
  return null;
}

export async function hmacSha256Hex(
  secret: string,
  value: string,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(signature))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function hashClient(value: string): Promise<string> {
  const secret = requiredHmacSecret();
  return hmacSha256Hex(secret, value.split(",")[0].trim());
}

async function resolveAuthenticatedUser(
  request: Request,
): Promise<string | null> {
  const authorization = request.headers.get("authorization")?.trim();
  if (!authorization) return null;
  if (!/^Bearer\s+\S+$/i.test(authorization)) {
    throw new AuthenticationError();
  }

  const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
  const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  try {
    const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: {
        apikey: serviceRoleKey,
        authorization,
      },
    });
    if (!response.ok) throw new AuthenticationError();
    const body = await response.json().catch(() => null) as
      | { id?: unknown }
      | null;
    const userId = stringValue(body?.id);
    if (!UUID_PATTERN.test(userId)) throw new AuthenticationError();
    return userId;
  } catch (error) {
    if (error instanceof AuthenticationError) throw error;
    throw new AuthenticationError();
  }
}

async function enqueueContactSupportRequest(
  databaseUrl: string,
  requestId: string,
  body: NormalizedContactRequest,
  emailHash: string,
  authUserIdHash: string | null,
  ipHash: string,
): Promise<QueueResult> {
  const payload: OutboxPayload = {
    email: body.email,
    name: body.name,
    message: body.message,
    source: body.source,
    platform: body.platform,
    appVersion: body.appVersion,
    buildType: body.buildType,
    isSignedIn: authUserIdHash !== null,
  };
  const encrypted = await encryptContactSupportPayload(
    payload,
    outboxEncryptionSecret(),
  );
  const sql = postgres(databaseUrl, { prepare: false });

  try {
    const rows = await sql`
      select request_id::text as request_id
      from public.enqueue_contact_support_request(
        ${requestId},
        ${body.source},
        ${body.idempotencyKey},
        ${emailHash},
        ${authUserIdHash},
        ${ipHash},
        ${body.platform},
        ${body.appVersion},
        ${body.buildType},
        ${JSON.stringify(encrypted.envelope)}::jsonb,
        ${encrypted.payloadHash}
      )
    `;
    const queuedRequestId = stringValue(rows[0]?.request_id);
    if (!UUID_PATTERN.test(queuedRequestId)) {
      return { ok: false, status: 500, error: "queue_failed" };
    }
    return { ok: true, requestId: queuedRequestId };
  } catch (error) {
    const message = error instanceof Error ? error.message.toLowerCase() : "";
    if (message.includes("contact_support_rate_limited_email")) {
      return { ok: false, status: 429, error: "rate_limited_email" };
    }
    if (message.includes("contact_support_rate_limited")) {
      return { ok: false, status: 429, error: "rate_limited" };
    }
    if (message.includes("contact_support_idempotency_key_reused")) {
      return { ok: false, status: 409, error: "idempotency_key_reused" };
    }
    return { ok: false, status: 500, error: "queue_failed" };
  } finally {
    await sql.end();
  }
}

function clientIp(request: Request): string {
  const value = request.headers.get("cf-connecting-ip") ??
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ??
    "unknown";
  return value.trim().slice(0, MAX_FIELD_BYTES) || "unknown";
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function textByteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function requiredHmacSecret(): string {
  const secret = requiredEnv("CONTACT_RATE_LIMIT_SALT");
  const size = textByteLength(secret);
  if (
    size < MIN_RATE_LIMIT_SECRET_BYTES || size > MAX_RATE_LIMIT_SECRET_BYTES
  ) {
    throw new ConfigurationError();
  }
  return secret;
}

function outboxEncryptionSecret(): string {
  const secret = requiredEnv("CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY");
  try {
    validateEncryptionSecret(secret);
  } catch {
    throw new ConfigurationError("CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY");
  }
  return secret;
}

async function healthResponse(): Promise<Response> {
  try {
    requiredEnv("SUPABASE_URL");
    requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
    requiredEnv("SUPABASE_DB_URL");
    requiredEnv("CONTACT_SUPPORT_WORKER_SECRET");
    requiredEnv("RESEND_API_KEY");
    requiredEnv("CONTACT_TO_EMAIL");
    requiredEnv("CONTACT_FROM_EMAIL");
    outboxEncryptionSecret();

    const sql = postgres(requiredEnv("SUPABASE_DB_URL"), { prepare: false });
    try {
      const rows = await sql`
        select healthy
        from public.contact_support_health()
      ` as Array<{ healthy?: boolean }>;
      if (rows[0]?.healthy !== true) {
        return jsonResponse(
          { status: "degraded", service: "contact-support" },
          503,
        );
      }
      return jsonResponse({ status: "ok", service: "contact-support" }, 200);
    } finally {
      await sql.end();
    }
  } catch (error) {
    console.error("contact-support health failure", safeErrorCode(error));
    return jsonResponse(
      { status: "degraded", service: "contact-support" },
      503,
    );
  }
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new ConfigurationError(name);
  return value;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function safeErrorCode(error: unknown): string {
  if (error instanceof ConfigurationError) return "configuration_error";
  if (error instanceof AuthenticationError) return "authentication_error";
  if (error instanceof RequestTooLargeError) return "payload_too_large";
  return "unexpected_error";
}

class RequestTooLargeError extends Error {}
class AuthenticationError extends Error {}
class ConfigurationError extends Error {
  constructor(name = "configuration") {
    super(name);
  }
}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
