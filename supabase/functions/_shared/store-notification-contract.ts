export type StoreProvider = "app_store" | "google_play";
export type StoreNotificationAction = "activate" | "revoke" | "preserve" | "noop";
export type StoreSubscriptionState =
  | "active"
  | "billing_retry"
  | "grace_period"
  | "on_hold"
  | "paused"
  | "canceled"
  | "revoked"
  | "refunded"
  | "expired"
  | "pending"
  | "unsupported";

export type StoreNotificationCommand = {
  provider: StoreProvider;
  notificationId: string;
  eventId: string;
  eventType: string;
  subscriptionKey: string;
  purchaseTokenHash: string | null;
  storeTransactionId: string | null;
  storeProductId: string | null;
  userId: string | null;
  action: StoreNotificationAction;
  subscriptionState: StoreSubscriptionState;
  expiresAt: string | null;
  occurredAt: string;
  signatureVerified: true;
  detail: Record<string, string | number | boolean | null>;
};

export type StoreNotificationResult = {
  event_id: string;
  result: "received" | "applied" | "ignored" | "rejected";
  grant_id: string | null;
  user_id: string | null;
  failure_reason: string | null;
  grant_changed: boolean;
};

export type StoreReconciliationTarget = {
  event_id: string;
  provider: StoreProvider;
  subscription_key: string;
  purchase_token_hash: string | null;
  store_product_id: string | null;
  store_transaction_id: string | null;
  original_transaction_id: string | null;
  user_id: string | null;
  binding_status: "verified" | "not_verified";
  failure_reason: string | null;
};

export const MAX_NOTIFICATION_BODY_BYTES = 512 * 1024;
export const MAX_RECONCILIATION_BATCH = 20;

// Keep provider configuration names in one contract so Edge Functions and
// deployment runbooks cannot silently drift apart. Values are never logged.
export const GOOGLE_PLAY_ENV_NAMES = {
  packageName: "GOOGLE_PLAY_PACKAGE_NAME",
  pushAudience: "GOOGLE_RTDN_PUSH_AUDIENCE",
  pushServiceAccountEmail: "GOOGLE_PUBSUB_EXPECTED_SERVICE_ACCOUNT_EMAIL",
  developerClientEmail: "GOOGLE_PLAY_SERVICE_ACCOUNT_CLIENT_EMAIL",
  developerPrivateKey: "GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY",
  developerPrivateKeyId: "GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY_ID",
  oidcJwksUrl: "GOOGLE_PUBSUB_OIDC_JWKS_URL",
} as const;

export const GOOGLE_PLAY_DEVELOPER_API_SCOPE =
  "https://www.googleapis.com/auth/androidpublisher";

export function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_${name.toLowerCase()}`);
  return value;
}

export function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function recordValue(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export function validUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

export function isoFromMillis(value: unknown): string | null {
  const raw = typeof value === "number"
    ? value
    : typeof value === "string" && value.trim()
    ? Number(value)
    : Number.NaN;
  if (!Number.isFinite(raw) || raw <= 0) return null;
  const date = new Date(raw);
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}

export function isoFromDateString(value: unknown): string | null {
  if (typeof value !== "string" || !value.trim()) return null;
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toISOString() : null;
}

export async function sha256Hex(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export function decodeBase64(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/").replace(/\s/g, "");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

export async function readBodyText(request: Request, maxBytes: number): Promise<string> {
  const declaredLength = Number(request.headers.get("content-length") ?? "");
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new Error("payload_too_large");
  }

  const reader = request.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      total += next.value.byteLength;
      if (total > maxBytes) throw new Error("payload_too_large");
      chunks.push(next.value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(result);
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export function safeDetail(value: Record<string, unknown>): Record<string, string | number | boolean | null> {
  const result: Record<string, string | number | boolean | null> = {};
  for (const [key, item] of Object.entries(value)) {
    if (!/^[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(key)) continue;
    if (typeof item === "string" && item.length <= 256) result[key] = item;
    else if (typeof item === "number" && Number.isFinite(item)) result[key] = item;
    else if (typeof item === "boolean") result[key] = item;
    else if (item === null) result[key] = null;
  }
  return result;
}

function restHeaders(serviceRoleKey: string): HeadersInit {
  return {
    apikey: serviceRoleKey,
    authorization: `Bearer ${serviceRoleKey}`,
    "content-type": "application/json",
  };
}

export async function applyStoreNotification(
  supabaseUrl: string,
  serviceRoleKey: string,
  command: StoreNotificationCommand,
): Promise<StoreNotificationResult> {
  const response = await fetch(`${supabaseUrl.replace(/\/+$/, "")}/rest/v1/rpc/apply_store_subscription_notification`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey),
    body: JSON.stringify({
      p_provider: command.provider,
      p_notification_id: command.notificationId,
      p_event_id: command.eventId,
      p_event_type: command.eventType,
      p_subscription_key: command.subscriptionKey,
      p_purchase_token_hash: command.purchaseTokenHash,
      p_store_transaction_id: command.storeTransactionId,
      p_store_product_id: command.storeProductId,
      p_user_id: command.userId,
      p_action: command.action,
      p_subscription_state: command.subscriptionState,
      p_expires_at: command.expiresAt,
      p_occurred_at: command.occurredAt,
      p_signature_verified: command.signatureVerified,
      p_detail: command.detail,
    }),
  });
  return readRpcResult(response, "store_notification_apply_failed");
}

export async function reconcileStoreNotification(
  supabaseUrl: string,
  serviceRoleKey: string,
  eventId: string,
): Promise<StoreNotificationResult> {
  const response = await fetch(`${supabaseUrl.replace(/\/+$/, "")}/rest/v1/rpc/reconcile_store_subscription_notification`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey),
    body: JSON.stringify({ p_event_row_id: eventId }),
  });
  return readRpcResult(response, "store_notification_reconciliation_failed");
}

export async function prepareStoreSubscriptionReconciliation(
  supabaseUrl: string,
  serviceRoleKey: string,
  eventId: string,
  binding: {
    purchaseTokenHash?: string | null;
    storeTransactionId?: string | null;
  } = {},
): Promise<StoreReconciliationTarget> {
  const response = await fetch(
    `${supabaseUrl.replace(/\/+$/, "")}/rest/v1/rpc/prepare_store_subscription_reconciliation`,
    {
      method: "POST",
      headers: restHeaders(serviceRoleKey),
      body: JSON.stringify({
        p_event_row_id: eventId,
        p_purchase_token_hash: binding.purchaseTokenHash ?? null,
        p_store_transaction_id: binding.storeTransactionId ?? null,
      }),
    },
  );
  if (!response.ok) throw new Error(await readRpcErrorCode(response, "store_reconciliation_target_failed"));
  const body = await response.json().catch(() => null);
  const row = Array.isArray(body) ? body[0] : body;
  if (!row || typeof row.event_id !== "string" || typeof row.provider !== "string") {
    throw new Error("store_reconciliation_target_invalid_response");
  }
  if (row.provider !== "app_store" && row.provider !== "google_play") {
    throw new Error("store_reconciliation_target_invalid_provider");
  }
  if (row.binding_status !== "verified" && row.binding_status !== "not_verified") {
    throw new Error("store_reconciliation_target_invalid_binding");
  }
  return {
    event_id: row.event_id,
    provider: row.provider,
    subscription_key: typeof row.subscription_key === "string" ? row.subscription_key : "",
    purchase_token_hash: typeof row.purchase_token_hash === "string" ? row.purchase_token_hash : null,
    store_product_id: typeof row.store_product_id === "string" ? row.store_product_id : null,
    store_transaction_id: typeof row.store_transaction_id === "string" ? row.store_transaction_id : null,
    original_transaction_id: typeof row.original_transaction_id === "string"
      ? row.original_transaction_id
      : null,
    user_id: typeof row.user_id === "string" ? row.user_id : null,
    binding_status: row.binding_status,
    failure_reason: typeof row.failure_reason === "string" ? row.failure_reason : null,
  };
}

export async function applyStoreSubscriptionReconciliation(
  supabaseUrl: string,
  serviceRoleKey: string,
  snapshot: {
    eventId: string;
    purchaseTokenHash: string | null;
    storeTransactionId: string | null;
    storeProductId: string;
    action: StoreNotificationAction;
    subscriptionState: StoreSubscriptionState;
    expiresAt: string | null;
    observedAt: string | null;
    signedTransactionInfoHash: string | null;
  },
): Promise<StoreNotificationResult> {
  const response = await fetch(
    `${supabaseUrl.replace(/\/+$/, "")}/rest/v1/rpc/apply_store_subscription_reconciliation`,
    {
      method: "POST",
      headers: restHeaders(serviceRoleKey),
      body: JSON.stringify({
        p_event_row_id: snapshot.eventId,
        p_purchase_token_hash: snapshot.purchaseTokenHash,
        p_store_transaction_id: snapshot.storeTransactionId,
        p_store_product_id: snapshot.storeProductId,
        p_action: snapshot.action,
        p_subscription_state: snapshot.subscriptionState,
        p_expires_at: snapshot.expiresAt,
        p_observed_at: snapshot.observedAt,
        p_signed_transaction_info_sha256: snapshot.signedTransactionInfoHash,
      }),
    },
  );
  return readRpcResult(response, "store_reconciliation_apply_failed");
}

async function readRpcResult(response: Response, errorCode: string): Promise<StoreNotificationResult> {
  if (!response.ok) throw new Error(errorCode);
  const body = await response.json().catch(() => null);
  const row = Array.isArray(body) ? body[0] : body;
  if (!row || typeof row.event_id !== "string" || typeof row.result !== "string") {
    throw new Error(`${errorCode}_invalid_response`);
  }
  return {
    event_id: row.event_id,
    result: row.result,
    grant_id: typeof row.grant_id === "string" ? row.grant_id : null,
    user_id: typeof row.user_id === "string" ? row.user_id : null,
    failure_reason: typeof row.failure_reason === "string" ? row.failure_reason : null,
    grant_changed: row.grant_changed === true,
  };
}

async function readRpcErrorCode(response: Response, fallback: string): Promise<string> {
  const body = recordValue(await response.json().catch(() => null));
  const candidates = [body.message, body.error, body.code];
  for (const candidate of candidates) {
    if (typeof candidate === "string" && /^[a-z0-9_:-]{1,96}$/.test(candidate)) return candidate;
  }
  return fallback;
}

export function constantTimeStringEqual(left: string, right: string): boolean {
  const leftBytes = new TextEncoder().encode(left);
  const rightBytes = new TextEncoder().encode(right);
  let difference = leftBytes.length ^ rightBytes.length;
  const length = Math.max(leftBytes.length, rightBytes.length);
  for (let index = 0; index < length; index += 1) {
    difference |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }
  return difference === 0;
}
