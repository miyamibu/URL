import {
  decryptContactSupportPayload,
  validateEncryptionSecret,
} from "../_shared/contact-support-outbox-crypto.ts";

type WorkerConfig = {
  supabaseUrl: string;
  serviceRoleKey: string;
  workerSecret: string;
  resendApiKey: string;
  contactToEmail: string;
  contactFromEmail: string;
  encryptionKey: string;
};

type WorkerRequest = {
  limit: number;
};

type OutboxPayload = {
  email: string;
  name: string;
  message: string;
  source: string;
  platform: "android" | "ios";
  appVersion: string;
  buildType: "debug" | "release";
  isSignedIn: boolean;
};

type ClaimedOutbox = {
  outboxId: string;
  requestId: string;
  payload: unknown;
  leaseToken: string;
  attempts: number;
};

type SendResult =
  | { ok: true; messageId: string }
  | { ok: false; error: string };

const MAX_BODY_BYTES = 8 * 1024;
const MAX_PAYLOAD_BYTES = 16 * 1024;
const DEFAULT_BATCH_LIMIT = 10;
const MAX_BATCH_LIMIT = 50;
const LEASE_SECONDS = 10 * 60;
const RESEND_TIMEOUT_MS = 15 * 1000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const SOURCE_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
};

export async function handleRequest(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  let config: WorkerConfig;
  try {
    config = readConfig();
  } catch {
    return jsonResponse({ error: "worker_not_configured" }, 500);
  }

  if (!authorizeWorker(request, config)) {
    return jsonResponse({ error: "unauthorized" }, 401);
  }

  let workerRequest: WorkerRequest;
  try {
    workerRequest = parseWorkerRequest(await readBodyText(request));
  } catch (error) {
    if (error instanceof BodyTooLargeError) {
      return jsonResponse({ error: "payload_too_large" }, 413);
    }
    return jsonResponse({ error: "invalid_request" }, 400);
  }

  try {
    await recordWorkerHeartbeat(config);
  } catch {
    return jsonResponse({ error: "heartbeat_failed" }, 500);
  }

  let claims: ClaimedOutbox[];
  try {
    claims = await claimOutbox(config, workerRequest.limit);
  } catch {
    return jsonResponse({ error: "claim_failed" }, 500);
  }

  let sent = 0;
  let failed = 0;
  let operationalFailure = false;

  for (const claim of claims) {
    try {
      const result = await processClaim(config, claim);
      if (result === "sent") sent += 1;
      else failed += 1;
    } catch {
      // If completion/failure recording itself is unavailable, surface a
      // non-2xx result so the scheduler retries after the lease expires.
      operationalFailure = true;
    }
  }

  if (operationalFailure) {
    return jsonResponse({ error: "worker_recording_failed" }, 500);
  }

  return jsonResponse({ claimed: claims.length, sent, failed }, 200);
}

export function parseWorkerRequest(body: string): WorkerRequest {
  if (!body.trim()) return { limit: DEFAULT_BATCH_LIMIT };

  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    throw new Error("invalid_json");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("invalid_body");
  }

  const record = parsed as Record<string, unknown>;
  if (Object.keys(record).some((key) => key !== "limit")) {
    throw new Error("unsupported_field");
  }
  if (record.limit === undefined) return { limit: DEFAULT_BATCH_LIMIT };
  if (
    typeof record.limit !== "number" ||
    !Number.isInteger(record.limit) ||
    record.limit < 1 ||
    record.limit > MAX_BATCH_LIMIT
  ) {
    throw new Error("invalid_limit");
  }
  return { limit: record.limit };
}

export async function readBodyText(request: Request): Promise<string> {
  const contentLength = Number(request.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > MAX_BODY_BYTES) {
    throw new BodyTooLargeError();
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
      if (totalBytes > MAX_BODY_BYTES) throw new BodyTooLargeError();
      chunks.push(value);
    }
  } catch (error) {
    await reader.cancel().catch(() => undefined);
    throw error;
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

export function parseOutboxPayload(value: unknown): OutboxPayload | null {
  const payload = typeof value === "string" ? tryParseJson(value) : value;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
    return null;
  }
  try {
    if (
      new TextEncoder().encode(JSON.stringify(payload)).byteLength >
        MAX_PAYLOAD_BYTES
    ) {
      return null;
    }
  } catch {
    return null;
  }

  const candidate = payload as Record<string, unknown>;
  const email = boundedString(candidate.email, 320);
  const name = boundedString(candidate.name, 512);
  const message = boundedString(candidate.message, 8 * 1024);
  const source = boundedString(candidate.source, 128);
  const appVersion = boundedString(candidate.appVersion, 512);
  const platform = candidate.platform;
  const buildType = candidate.buildType;

  if (
    !email || !name || !message || !source || !appVersion ||
    !SOURCE_PATTERN.test(source) ||
    (platform !== "android" && platform !== "ios") ||
    (buildType !== "debug" && buildType !== "release") ||
    typeof candidate.isSignedIn !== "boolean"
  ) {
    return null;
  }

  return {
    email,
    name,
    message,
    source,
    platform,
    appVersion,
    buildType,
    isSignedIn: candidate.isSignedIn,
  };
}

export async function decryptOutboxPayload(
  value: unknown,
  encryptionKey: string,
): Promise<OutboxPayload | null> {
  try {
    return parseOutboxPayload(
      await decryptContactSupportPayload(value, encryptionKey),
    );
  } catch {
    return null;
  }
}

export async function extractResendMessageId(
  response: Response,
): Promise<string | null> {
  const body = await response.json().catch(() => null) as {
    id?: unknown;
  } | null;
  const messageId = typeof body?.id === "string" ? body.id.trim() : "";
  return messageId && messageId.length <= 256 ? messageId : null;
}

export async function sendWithResend(
  config: WorkerConfig,
  requestId: string,
  body: OutboxPayload,
): Promise<SendResult> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), RESEND_TIMEOUT_MS);
  try {
    const response = await fetch("https://api.resend.com/emails", {
      method: "POST",
      headers: {
        authorization: `Bearer ${config.resendApiKey}`,
        "content-type": "application/json",
        // The request UUID is stable across worker retries.
        "idempotency-key": requestId,
      },
      body: JSON.stringify({
        from: config.contactFromEmail,
        to: [config.contactToEmail],
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
        ].join("\n"),
      }),
      signal: controller.signal,
    });

    if (!response.ok) return { ok: false, error: "resend_send_failed" };
    const messageId = await extractResendMessageId(response);
    if (!messageId) {
      return { ok: false, error: "resend_message_id_missing" };
    }
    return { ok: true, messageId };
  } catch {
    return { ok: false, error: "resend_request_failed" };
  } finally {
    clearTimeout(timeout);
  }
}

function readConfig(): WorkerConfig {
  const supabaseUrl = requiredEnv("SUPABASE_URL", 1);
  const parsedUrl = new URL(supabaseUrl);
  if (parsedUrl.protocol !== "https:") throw new Error("invalid_supabase_url");

  return {
    supabaseUrl: supabaseUrl.replace(/\/+$/, ""),
    serviceRoleKey: requiredEnv("SUPABASE_SERVICE_ROLE_KEY", 32),
    workerSecret: requiredEnv("CONTACT_SUPPORT_WORKER_SECRET", 32),
    resendApiKey: requiredEnv("RESEND_API_KEY", 1),
    contactToEmail: requiredEnv("CONTACT_TO_EMAIL", 1),
    contactFromEmail: requiredEnv("CONTACT_FROM_EMAIL", 1),
    encryptionKey: requiredEncryptionKey(),
  };
}

function requiredEnv(name: string, minimumLength: number): string {
  const value = Deno.env.get(name)?.trim() ?? "";
  if (!value || value.length < minimumLength) {
    throw new Error("missing_configuration");
  }
  return value;
}

function requiredEncryptionKey(): string {
  const value = requiredEnv("CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY", 1);
  try {
    validateEncryptionSecret(value);
  } catch {
    throw new Error("invalid_encryption_key");
  }
  return value;
}

export function authorizeWorker(
  request: Request,
  config: Pick<WorkerConfig, "serviceRoleKey" | "workerSecret">,
): boolean {
  const authorization = request.headers.get("authorization") ?? "";
  const bearer = authorization.match(/^Bearer\s+(.+)$/i)?.[1]?.trim() ?? "";
  const workerSecret = request.headers.get("x-contact-support-worker-secret")
    ?.trim() ?? "";
  return constantTimeEqual(bearer, config.serviceRoleKey) &&
    constantTimeEqual(workerSecret, config.workerSecret);
}

async function claimOutbox(
  config: WorkerConfig,
  limit: number,
): Promise<ClaimedOutbox[]> {
  const response = await postRpc(config, "claim_contact_support_outbox_batch", {
    p_limit: limit,
    p_lease_seconds: LEASE_SECONDS,
  });
  const body = await response.json().catch(() => null) as unknown;
  if (!Array.isArray(body) || body.length > MAX_BATCH_LIMIT) {
    throw new Error("invalid_claim_response");
  }
  return body.map(parseClaimedOutbox);
}

function parseClaimedOutbox(value: unknown): ClaimedOutbox {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("invalid_claim_row");
  }
  const row = value as Record<string, unknown>;
  const outboxId = stringValue(row.outbox_id);
  const requestId = stringValue(row.request_id);
  const leaseToken = stringValue(row.lease_token);
  const attempts = row.attempts;
  if (
    !UUID_PATTERN.test(outboxId) ||
    !UUID_PATTERN.test(requestId) ||
    !UUID_PATTERN.test(leaseToken) ||
    typeof attempts !== "number" ||
    !Number.isInteger(attempts) ||
    attempts < 1
  ) {
    throw new Error("invalid_claim_row");
  }
  return { outboxId, requestId, payload: row.payload, leaseToken, attempts };
}

async function processClaim(
  config: WorkerConfig,
  claim: ClaimedOutbox,
): Promise<"sent" | "failed"> {
  const payload = await decryptOutboxPayload(
    claim.payload,
    config.encryptionKey,
  );
  if (!payload) {
    await failOutbox(config, claim, "invalid_outbox_payload");
    return "failed";
  }

  const result = await sendWithResend(config, claim.requestId, payload);
  if (!result.ok) {
    await failOutbox(config, claim, result.error);
    return "failed";
  }

  try {
    await postRpc(config, "complete_contact_support_outbox", {
      p_outbox_id: claim.outboxId,
      p_request_id: claim.requestId,
      p_message_id: result.messageId,
      p_lease_token: claim.leaseToken,
    });
  } catch (error) {
    // Best effortly release the lease for a retry. If this also fails, the
    // outer handler returns 500 and the lease-expiry path remains available.
    await failOutbox(config, claim, "complete_record_failed").catch(() => {
      throw error;
    });
    throw error;
  }
  return "sent";
}

async function recordWorkerHeartbeat(config: WorkerConfig): Promise<void> {
  await postRpc(config, "record_contact_support_worker_heartbeat", {
    p_worker_name: "contact-support-outbox",
    p_run_at: new Date().toISOString(),
  });
}

async function failOutbox(
  config: WorkerConfig,
  claim: ClaimedOutbox,
  errorCode: string,
): Promise<void> {
  await postRpc(config, "fail_contact_support_outbox", {
    p_outbox_id: claim.outboxId,
    p_request_id: claim.requestId,
    p_error_code: errorCode,
    p_lease_token: claim.leaseToken,
  });
}

async function postRpc(
  config: WorkerConfig,
  functionName: string,
  body: Record<string, unknown>,
): Promise<Response> {
  const response = await fetch(
    `${config.supabaseUrl}/rest/v1/rpc/${functionName}`,
    {
      method: "POST",
      headers: {
        apikey: config.serviceRoleKey,
        authorization: `Bearer ${config.serviceRoleKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
    },
  );
  if (!response.ok) {
    if (response.body) await response.body.cancel().catch(() => undefined);
    throw new Error("rpc_failed");
  }
  return response;
}

function boundedString(value: unknown, maxBytes: number): string {
  if (typeof value !== "string") return "";
  const result = value.trim();
  return new TextEncoder().encode(result).byteLength <= maxBytes ? result : "";
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function tryParseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function constantTimeEqual(left: string, right: string): boolean {
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  let difference = leftBytes.length ^ rightBytes.length;
  const maxLength = Math.max(leftBytes.length, rightBytes.length);
  for (let index = 0; index < maxLength; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...JSON_HEADERS, "cache-control": "no-store" },
  });
}

class BodyTooLargeError extends Error {}

if (import.meta.main) {
  Deno.serve(handleRequest);
}
