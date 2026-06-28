import postgres from "npm:postgres@3.4.5";

type ContactSupportRequest = {
  email?: unknown;
  name?: unknown;
  message?: unknown;
  source?: unknown;
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
  platform: string;
  appVersion: string;
  buildType: string;
  isSignedIn: boolean;
  authUserId: string | null;
};

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const MAX_MESSAGE_LENGTH = 4000;
const EMAIL_HOURLY_LIMIT = 10;
const IP_HOURLY_LIMIT = 50;
const IP_DAILY_LIMIT = 200;
const VALID_PLATFORMS = new Set(["android", "ios"]);
const VALID_BUILD_TYPES = new Set(["debug", "release"]);

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
    const normalized = normalizeContactRequest(body);

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
    const rateLimit = await enforceRateLimit(databaseUrl, ipHash, emailHash);
    if (!rateLimit.ok) {
      return jsonResponse({ error: rateLimit.error, requestId }, rateLimit.status);
    }

    const queued = await recordSubmission(databaseUrl, requestId, normalized, emailHash, authUserIdHash, ipHash);
    if (!queued.ok) {
      return jsonResponse({ error: queued.error, requestId }, queued.status);
    }

    const sent = await sendWithResend(resendApiKey, contactFromEmail, contactToEmail, requestId, normalized);
    await updateSubmissionStatus(
      databaseUrl,
      queued.id,
      sent.ok ? "sent" : "failed",
      sent.ok ? sent.messageId : null,
      sent.ok ? null : sent.error,
    );
    if (!sent.ok) {
      return jsonResponse({ error: "send_failed", requestId }, 502);
    }

    return jsonResponse({ requestId, status: "accepted" }, 202);
  } catch (error) {
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
  try {
    const body = await request.json();
    return body && typeof body === "object" ? body as ContactSupportRequest : {};
  } catch {
    return {};
  }
}

function normalizeContactRequest(body: ContactSupportRequest): NormalizedContactRequest {
  const platform = stringValue(body.platform);
  return {
    email: stringValue(body.email).toLowerCase(),
    name: stringValue(body.name),
    message: stringValue(body.message),
    source: stringValue(body.source) || (platform ? `mobile:${platform}` : "unknown"),
    platform: platform || "unknown",
    appVersion: stringValue(body.appVersion) || "unknown",
    buildType: stringValue(body.buildType) || "unknown",
    isSignedIn: body.isSignedIn === true,
    authUserId: stringValue(body.authUserId) || null,
  };
}

function validateContactRequest(body: NormalizedContactRequest): string | null {
  if (!body.email || !body.name || !body.message) {
    return "missing_required_fields";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(body.email)) {
    return "invalid_email";
  }
  if (body.message.length > MAX_MESSAGE_LENGTH) {
    return "message_too_long";
  }
  if (!VALID_PLATFORMS.has(body.platform)) {
    return "invalid_platform";
  }
  if (!VALID_BUILD_TYPES.has(body.buildType)) {
    return "invalid_build_type";
  }
  return null;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function clientIp(request: Request): string {
  return request.headers.get("cf-connecting-ip") ??
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ??
    "unknown";
}

async function enforceRateLimit(
  databaseUrl: string,
  ipHash: string,
  emailHash: string,
): Promise<{ ok: true } | { ok: false; status: number; error: string }> {
  const sql = postgres(databaseUrl, { prepare: false });
  try {
    const [emailHourly, ipHourly, ipDaily] = await Promise.all([
      sql`
        select count(*)::int as count
        from public.contact_support_requests
        where created_at > now() - interval '1 hour'
          and email_hash = ${emailHash}
      `,
      sql`
        select count(*)::int as count
        from public.contact_support_requests
        where created_at > now() - interval '1 hour'
          and ip_hash = ${ipHash}
      `,
      sql`
        select count(*)::int as count
        from public.contact_support_requests
        where created_at > now() - interval '24 hour'
          and ip_hash = ${ipHash}
      `,
    ]);

    if (Number(emailHourly[0]?.count ?? 0) >= EMAIL_HOURLY_LIMIT) {
      return { ok: false, status: 429, error: "rate_limited_email" };
    }
    if (Number(ipHourly[0]?.count ?? 0) >= IP_HOURLY_LIMIT || Number(ipDaily[0]?.count ?? 0) >= IP_DAILY_LIMIT) {
      return { ok: false, status: 429, error: "rate_limited" };
    }
    return { ok: true };
  } finally {
    await sql.end();
  }
}

async function recordSubmission(
  databaseUrl: string,
  requestId: string,
  body: NormalizedContactRequest,
  emailHash: string,
  authUserIdHash: string | null,
  ipHash: string,
): Promise<{ ok: true; id: string } | { ok: false; status: number; error: string }> {
  const sql = postgres(databaseUrl, { prepare: false });
  try {
    const rows = await sql`
      insert into public.contact_support_requests (
        request_id,
        email_hash,
        auth_user_id_hash,
        ip_hash,
        platform,
        app_version,
        build_type,
        is_signed_in,
        delivery_status
      )
      values (
        ${requestId},
        ${emailHash},
        ${authUserIdHash},
        ${ipHash},
        ${body.platform},
        ${body.appVersion},
        ${body.buildType},
        ${body.isSignedIn},
        'pending'
      )
      returning id
    `;
    const id = rows[0]?.id;
    if (!id) return { ok: false, status: 500, error: "record_failed" };
    return { ok: true, id: String(id) };
  } catch (error) {
    console.error("recordSubmission failed", error);
    return { ok: false, status: 500, error: "record_failed" };
  } finally {
    await sql.end();
  }
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
    return { ok: false, error: await response.text() };
  }
  const responseBody = await response.json().catch(() => ({}));
  const messageId = typeof responseBody?.id === "string" ? responseBody.id : null;
  return { ok: true, messageId };
}

async function hashClient(value: string): Promise<string> {
  const salt = Deno.env.get("CONTACT_RATE_LIMIT_SALT") ?? "";
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
