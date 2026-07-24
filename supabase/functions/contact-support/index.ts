import postgres from "npm:postgres@3.4.5";

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
  isSignedIn?: unknown;
  authUserId?: unknown;
};

type NormalizedContactRequest = {
  email: string;
  name: string;
  message: string;
  source: string;
  idempotencyKey: string;
  platform: string;
  appVersion: string;
  buildType: string;
  isSignedIn: boolean;
  authUserId: string | null;
};

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, idempotency-key",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MAX_REQUEST_BYTES = 16 * 1024;
const MAX_MESSAGE_BYTES = 8 * 1024;
const MAX_FIELD_BYTES = 512;
const VALID_PLATFORMS = new Set(["android", "ios"]);
const VALID_BUILD_TYPES = new Set(["debug", "release"]);

class ContactPayloadTooLargeError extends Error {}

Deno.serve(async (request) => {
  const requestId = crypto.randomUUID();

  if (request.method === "OPTIONS") {
    return jsonResponse(null, 204);
  }
  if (request.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed", requestId }, 405);
  }

  try {
    const resendApiKey = requiredEnv("RESEND_API_KEY");
    const contactToEmail = requiredEnv("CONTACT_TO_EMAIL");
    const contactFromEmail = requiredEnv("CONTACT_FROM_EMAIL");
    const databaseUrl = requiredEnv("SUPABASE_DB_URL");

    const body = await readContactRequest(request);
    const normalized = normalizeContactRequest(body, request.headers.get("idempotency-key"));

    if (typeof body.honeypot === "string" && body.honeypot.trim()) {
      return jsonResponse({ requestId, status: "accepted" }, 202);
    }

    const validationError = validateContactRequest(normalized);
    if (validationError) {
      return jsonResponse({ error: validationError, requestId }, 400);
    }

    const emailHash = await hashClient(normalized.email);
    const authUserIdHash = normalized.authUserId ? await hashClient(normalized.authUserId) : null;
    const ipHash = await hashClient(clientIp(request));
    const reserved = await reserveContactSubmission(
      databaseUrl,
      requestId,
      normalized,
      emailHash,
      authUserIdHash,
      ipHash,
      await hashClient(normalized.idempotencyKey),
    );
    if (!reserved.ok) {
      return jsonResponse({ error: reserved.error, requestId }, reserved.status);
    }
    if (reserved.existing && reserved.deliveryStatus !== "pending" && reserved.deliveryStatus !== "failed") {
      return jsonResponse({ requestId: reserved.requestId, status: "accepted" }, 202);
    }

    // Reuse the durable request id on retries so the provider sees one
    // idempotency key even when the first attempt failed after reservation.
    const deliveryRequestId = reserved.requestId;
    const sent = await sendWithResend(
      resendApiKey,
      contactFromEmail,
      contactToEmail,
      deliveryRequestId,
      normalized,
    );
    await updateSubmissionStatus(
      databaseUrl,
      reserved.id,
      sent.ok ? "sent" : "failed",
      sent.ok ? sent.messageId : null,
      sent.ok ? null : sent.error,
    );
    if (!sent.ok) {
      return jsonResponse({ error: "send_failed", requestId }, 502);
    }

    return jsonResponse({ requestId: reserved.requestId, status: "accepted" }, 202);
  } catch (error) {
    if (error instanceof ContactPayloadTooLargeError) {
      return jsonResponse({ error: "request_too_large", requestId }, 413);
    }
    console.error("contact-support failed", error);
    return jsonResponse({ error: "server_error", requestId }, 502);
  }
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

async function readContactRequest(request: Request): Promise<ContactSupportRequest> {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) {
    throw new ContactPayloadTooLargeError("request_too_large");
  }
  try {
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.byteLength > MAX_REQUEST_BYTES) {
      throw new ContactPayloadTooLargeError("request_too_large");
    }
    const body = JSON.parse(new TextDecoder().decode(bytes));
    return body && typeof body === "object" ? body as ContactSupportRequest : {};
  } catch (error) {
    if (error instanceof ContactPayloadTooLargeError) throw error;
    return {};
  }
}

function normalizeContactRequest(body: ContactSupportRequest, headerIdempotencyKey: string | null): NormalizedContactRequest {
  const platform = stringValue(body.platform);
  return {
    email: stringValue(body.email).toLowerCase(),
    name: stringValue(body.name),
    message: stringValue(body.message),
    source: stringValue(body.source) || (platform ? `mobile:${platform}` : "unknown"),
    idempotencyKey: stringValue(headerIdempotencyKey) || stringValue(body.idempotencyKey),
    platform: platform || "unknown",
    appVersion: stringValue(body.appVersion) || "unknown",
    buildType: stringValue(body.buildType) || "unknown",
    isSignedIn: body.isSignedIn === true,
    authUserId: stringValue(body.authUserId) || null,
  };
}

function validateContactRequest(body: NormalizedContactRequest): string | null {
  if (!body.email || !body.name || !body.message || !body.idempotencyKey) {
    return "missing_required_fields";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(body.email)) {
    return "invalid_email";
  }
  if (byteLength(body.message) > MAX_MESSAGE_BYTES) {
    return "message_too_long";
  }
  if (!VALID_PLATFORMS.has(body.platform)) {
    return "invalid_platform";
  }
  if (!VALID_BUILD_TYPES.has(body.buildType)) {
    return "invalid_build_type";
  }
  if (byteLength(body.email) > MAX_FIELD_BYTES || byteLength(body.name) > MAX_FIELD_BYTES ||
    byteLength(body.source) > MAX_FIELD_BYTES || byteLength(body.appVersion) > MAX_FIELD_BYTES ||
    byteLength(body.idempotencyKey) > MAX_FIELD_BYTES || !/^[A-Za-z0-9._~-]{16,128}$/.test(body.idempotencyKey)) {
    return "invalid_request";
  }
  return null;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function clientIp(request: Request): string {
  // Only the edge's canonical client-IP header is trusted. Arbitrary
  // x-forwarded-for values are user-controlled at this function boundary.
  return request.headers.get("cf-connecting-ip")?.trim().slice(0, 128) || "unknown";
}

async function reserveContactSubmission(
  databaseUrl: string,
  requestId: string,
  body: NormalizedContactRequest,
  emailHash: string,
  authUserIdHash: string | null,
  ipHash: string,
  idempotencyKeyHash: string,
): Promise<
  | { ok: true; id: string; requestId: string; deliveryStatus: string; existing: boolean }
  | { ok: false; status: number; error: string }
> {
  const sql = postgres(databaseUrl, { prepare: false });
  try {
    const rows = await sql`
      select * from public.reserve_contact_support_request(
        ${requestId},
        ${idempotencyKeyHash},
        ${emailHash},
        ${authUserIdHash},
        ${ipHash},
        ${body.platform},
        ${body.appVersion},
        ${body.buildType},
        ${body.isSignedIn}
      )
    `;
    const row = rows[0];
    if (!row?.id || !row?.request_id) return { ok: false, status: 500, error: "record_failed" };
    return {
      ok: true,
      id: String(row.id),
      requestId: String(row.request_id),
      deliveryStatus: String(row.delivery_status ?? "pending"),
      existing: row.existing === true,
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message.includes("rate_limited_email")) return { ok: false, status: 429, error: "rate_limited_email" };
    if (message.includes("rate_limited")) return { ok: false, status: 429, error: "rate_limited" };
    console.error("reserveContactSubmission failed", message.split("\n")[0]?.slice(0, 160));
    return { ok: false, status: 500, error: "record_failed" };
  } finally {
    await sql.end();
  }
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

async function updateSubmissionStatus(
  databaseUrl: string,
  id: string,
  status: string,
  messageId: string | null,
  errorMessage: string | null,
) {
  const sql = postgres(databaseUrl, { prepare: false });
  try {
    await sql`
      update public.contact_support_requests
      set delivery_status = ${status},
          delivery_provider = 'resend',
          delivery_message_id = ${messageId},
          delivery_event_type = case when ${status} = 'sent' then 'email.sent' else 'email.failed' end,
          delivery_event_at = now(),
          delivery_error = ${errorMessage}
      where id = ${id}
        and (
          case delivery_status
            when 'pending' then 0
            when 'sent' then 10
            when 'delivery_delayed' then 20
            when 'delivered' then 30
            else 40
          end <= case ${status}
            when 'pending' then 0
            when 'sent' then 10
            when 'delivery_delayed' then 20
            when 'delivered' then 30
            else 40
          end
          or delivery_event_at is null
        )
    `;
  } finally {
    await sql.end();
  }
}

async function sendWithResend(
  apiKey: string,
  from: string,
  to: string,
  requestId: string,
  body: NormalizedContactRequest,
): Promise<{ ok: true; messageId: string | null } | { ok: false; error: string }> {
  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      "Idempotency-Key": requestId,
    },
    body: JSON.stringify({
      from,
      to: [to],
      reply_to: body.email,
      subject: `りんばむ 問い合わせ ${requestId}`,
      text: [
        "りんばむ 問い合わせ",
        "",
        `受付ID: ${requestId}`,
        "",
        "メールアドレス:",
        body.email,
        "",
        "氏名:",
        body.name,
        "",
        "問い合わせ内容:",
        body.message,
        "",
        "診断情報:",
        `source: ${body.source}`,
        `platform: ${body.platform}`,
        `appVersion: ${body.appVersion}`,
        `buildType: ${body.buildType}`,
        `isSignedIn: ${body.isSignedIn}`,
        `authUserId: ${body.authUserId ? "present" : "none"}`,
      ].join("\n"),
    }),
  });

  if (!response.ok) {
    return { ok: false, error: "resend_send_failed" };
  }
  const responseBody = await response.json().catch(() => ({}));
  const messageId = typeof responseBody?.id === "string" ? responseBody.id : null;
  return { ok: true, messageId };
}

async function hashClient(value: string): Promise<string> {
  const salt = requiredEnv("CONTACT_RATE_LIMIT_SALT");
  const data = new TextEncoder().encode(`${salt}:${value.split(",")[0].trim()}`);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) {
    throw new Error(`Missing required env: ${name}`);
  }
  return value;
}
