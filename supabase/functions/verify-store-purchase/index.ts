import { SignJWT } from "npm:jose@5.9.6";
import { SignedDataVerifier, Environment } from "npm:@apple/app-store-server-library@3.1.0";
import { Buffer } from "node:buffer";
import { APPLE_ROOT_CERTIFICATES } from "./apple-root-certificates.ts";

type StorePurchaseRequest = {
  storePlatform?: unknown;
  storeProductId?: unknown;
  purchaseToken?: unknown;
  storeTransactionId?: unknown;
  appAccountToken?: unknown;
};

type NormalizedStorePurchaseRequest = {
  storePlatform: "google_play" | "app_store";
  storeProductId: string;
  purchaseToken: string;
  storeTransactionId: string;
};

class PurchasePayloadTooLargeError extends Error {}

type ProductPlan = {
  plan: "standard" | "pro";
  billingPeriod: "monthly" | "yearly";
};

type VerifiedPurchase = ProductPlan & {
  storePlatform: "google_play" | "app_store";
  storeProductId: string;
  storeTransactionId: string;
  originalTransactionId: string | null;
  subscriptionKey: string;
  expiresAt: string | null;
};

type AuthUser = {
  id: string;
};

const JSON_HEADERS = {
  "Content-Type": "application/json; charset=utf-8",
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const CANONICAL_APP_STORE_BUNDLE_ID = "com.mibu.codebridge.ios";
const CANONICAL_ANDROID_APPLICATION_ID = "jp.miyamibu.urlalbum";
const MAX_REQUEST_BYTES = 64 * 1024;
const MAX_PURCHASE_TOKEN_LENGTH = 32 * 1024;

let appleVerifierPromise: Promise<SignedDataVerifier> | null = null;
let googleAccessTokenPromise: Promise<string> | null = null;
let googleAccessTokenCache: { value: string; expiresAtMillis: number } | null = null;

export async function handleRequest(request: Request): Promise<Response> {
  if (request.method === "OPTIONS") return jsonResponse(null, 204);
  if (request.method !== "POST") return jsonResponse({ error: "method_not_allowed" }, 405);

  let user: AuthUser | null = null;
  let normalized: NormalizedStorePurchaseRequest | null = null;
  try {
    const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
    const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
    const authorization = request.headers.get("authorization") ?? "";
    user = await requireUser(supabaseUrl, serviceRoleKey, authorization);

    normalized = normalizeRequest(await readJson(request));
    const allowed = await reservePurchaseAttempt(supabaseUrl, serviceRoleKey, user.id);
    if (!allowed) throw new Error("rate_limited");
    const expected = await activePlanForProduct(
      supabaseUrl,
      serviceRoleKey,
      normalized.storePlatform,
      normalized.storeProductId,
    );
    if (!expected) {
      await recordVerification(supabaseUrl, serviceRoleKey, user.id, normalized, null, "failed", "unknown_product", null);
      return jsonResponse({ error: "unknown_product" }, 400);
    }

    const verified = normalized.storePlatform === "app_store"
      ? await verifyAppStorePurchase(normalized, expected, user.id)
      : await verifyGooglePlayPurchase(normalized, expected, user.id);

    const committed = await completePurchaseVerification(
      supabaseUrl,
      serviceRoleKey,
      user.id,
      normalized,
      verified,
    );
    const verificationId = committed.verificationId;
    const grantId = committed.grantId;

    return jsonResponse({
      status: "verified",
      verificationId,
      grantId,
      plan: verified.plan,
      billingPeriod: verified.billingPeriod,
    });
  } catch (error) {
    if (error instanceof PurchasePayloadTooLargeError) {
      return jsonResponse({ error: "request_too_large" }, 413);
    }
    const message = error instanceof Error ? error.message : "server_error";
    const safeReason = publicFailureReason(message);
    console.error("verify-store-purchase failed", safeReason);
    if (user && normalized && shouldRecordVerificationFailure(safeReason)) {
      try {
        const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
        const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
        await recordVerification(supabaseUrl, serviceRoleKey, user.id, normalized, null, "failed", publicFailureReason(message), null);
      } catch (recordError) {
        console.error(
          "failed to record purchase failure",
          publicFailureReason(recordError instanceof Error ? recordError.message : "record_failed"),
        );
      }
    }
    const status = safeReason === "auth_required"
      ? 401
      : safeReason === "invalid_request"
      ? 400
      : safeReason === "rate_limited"
      ? 429
      : safeReason === "purchase_already_claimed"
      ? 409
      : safeReason === "purchase_state_conflict"
      ? 409
      : 502;
    return jsonResponse({ error: safeReason }, status);
  }
}

if (import.meta.main) Deno.serve(handleRequest);

async function readJson(request: Request): Promise<StorePurchaseRequest> {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_REQUEST_BYTES) {
    throw new PurchasePayloadTooLargeError("request_too_large");
  }
  try {
    const bytes = new Uint8Array(await request.arrayBuffer());
    if (bytes.byteLength > MAX_REQUEST_BYTES) {
      throw new PurchasePayloadTooLargeError("request_too_large");
    }
    const body = JSON.parse(new TextDecoder().decode(bytes));
    return body && typeof body === "object" ? body as StorePurchaseRequest : {};
  } catch (error) {
    if (error instanceof PurchasePayloadTooLargeError) throw error;
    return {};
  }
}

export function normalizeRequest(body: StorePurchaseRequest): NormalizedStorePurchaseRequest {
  const storePlatform = stringValue(body.storePlatform);
  const storeProductId = stringValue(body.storeProductId);
  const purchaseToken = stringValue(body.purchaseToken);
  const storeTransactionId = stringValue(body.storeTransactionId);
  if (!["google_play", "app_store"].includes(storePlatform) || !storeProductId || !purchaseToken ||
    byteLength(storeProductId) > 128 || byteLength(purchaseToken) > MAX_PURCHASE_TOKEN_LENGTH ||
    byteLength(storeTransactionId) > 512) {
    throw new Error("invalid_request");
  }
  return {
    storePlatform: storePlatform as NormalizedStorePurchaseRequest["storePlatform"],
    storeProductId,
    purchaseToken,
    storeTransactionId,
  };
}

export function expectedPlanForProduct(productId: string): ProductPlan | null {
  const match = /^urlsaver\.(standard|pro)\.(monthly|yearly)$/.exec(productId);
  if (!match) return null;
  return { plan: match[1] as ProductPlan["plan"], billingPeriod: match[2] as ProductPlan["billingPeriod"] };
}

async function activePlanForProduct(
  supabaseUrl: string,
  serviceRoleKey: string,
  storePlatform: NormalizedStorePurchaseRequest["storePlatform"],
  storeProductId: string,
): Promise<ProductPlan | null> {
  const response = await fetch(
    `${supabaseUrl}/rest/v1/subscription_products?select=plan,billing_period&store_platform=eq.${storePlatform}&store_product_id=eq.${encodeURIComponent(storeProductId)}&is_active=eq.true&limit=1`,
    { headers: restHeaders(serviceRoleKey) },
  );
  if (!response.ok) throw new Error(`product_catalog_lookup_failed:${response.status}`);
  const row = (await response.json())?.[0];
  const catalogPlan = stringValue(row?.plan);
  const catalogPeriod = stringValue(row?.billing_period);
  const parsed = expectedPlanForProduct(storeProductId);
  if (!parsed || catalogPlan !== parsed.plan || catalogPeriod !== parsed.billingPeriod) return null;
  if (catalogPlan !== "standard" && catalogPlan !== "pro") return null;
  if (catalogPeriod !== "monthly" && catalogPeriod !== "yearly") return null;
  return { plan: catalogPlan, billingPeriod: catalogPeriod };
}

async function reservePurchaseAttempt(
  supabaseUrl: string,
  serviceRoleKey: string,
  userId: string,
): Promise<boolean> {
  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/reserve_store_purchase_attempt`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey),
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!response.ok) throw new Error("purchase_rate_limit_unavailable");
  const body = await response.json();
  if (typeof body === "boolean") return body;
  return body?.[0]?.reserve_store_purchase_attempt === true;
}

async function completePurchaseVerification(
  supabaseUrl: string,
  serviceRoleKey: string,
  userId: string,
  request: NormalizedStorePurchaseRequest,
  verified: VerifiedPurchase,
): Promise<{ verificationId: string; grantId: string }> {
  const response = await fetch(`${supabaseUrl}/rest/v1/rpc/complete_store_purchase_verification`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey),
    body: JSON.stringify({
      p_user_id: userId,
      p_store_platform: verified.storePlatform,
      p_store_product_id: verified.storeProductId,
      p_store_transaction_id: verified.storeTransactionId,
      p_purchase_token_hash: await sha256Hex(request.purchaseToken),
      p_plan: verified.plan,
      p_billing_period: verified.billingPeriod,
      p_original_transaction_id: verified.originalTransactionId,
      p_subscription_key: verified.subscriptionKey,
      p_expires_at: verified.expiresAt,
    }),
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    const reason = body.match(/purchase_already_claimed|invalid_verified_purchase/)?.[0];
    throw new Error(reason ?? "purchase_commit_failed");
  }
  const rows = await response.json();
  const row = Array.isArray(rows) ? rows[0] : rows;
  if (!row?.verification_id || !row?.grant_id) throw new Error("purchase_commit_failed");
  return { verificationId: String(row.verification_id), grantId: String(row.grant_id) };
}

async function requireUser(supabaseUrl: string, serviceRoleKey: string, authorization: string): Promise<AuthUser> {
  if (!authorization.toLowerCase().startsWith("bearer ")) throw new Error("auth_required");
  const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
    headers: {
      apikey: serviceRoleKey,
      authorization,
    },
  });
  if (!response.ok) throw new Error("auth_required");
  const body = await response.json();
  if (!body?.id || typeof body.id !== "string") throw new Error("auth_required");
  return { id: body.id };
}

async function verifyAppStorePurchase(
  request: NormalizedStorePurchaseRequest,
  expected: ProductPlan,
  userId: string,
): Promise<VerifiedPurchase> {
  const verifier = await appleVerifier();
  const payload = await verifier.verifyAndDecodeTransaction(request.purchaseToken);

  const productId = stringValue(payload.productId);
  const transactionId = stringValue(payload.transactionId);
  const originalTransactionId = stringValue(payload.originalTransactionId);
  if (!transactionId) throw new Error("transaction_missing");
  if (!originalTransactionId) throw new Error("original_transaction_missing");
  if (productId !== request.storeProductId) throw new Error("product_mismatch");
  if (request.storeTransactionId && transactionId !== request.storeTransactionId) throw new Error("transaction_mismatch");
  if (productId !== `urlsaver.${expected.plan}.${expected.billingPeriod}`) throw new Error("product_mismatch");
  if (stringValue(payload.type) !== "Auto-Renewable Subscription") throw new Error("app_store_product_type_mismatch");
  if (payload.revocationDate !== undefined && payload.revocationDate !== null) throw new Error("transaction_revoked");
  if (!(await constantTimeStringEqual(stringValue(payload.appAccountToken).toLowerCase(), userId.toLowerCase()))) {
    throw new Error("app_store_account_binding_mismatch");
  }

  const expiresAt = millisToIso(payload.expiresDate);
  if (!expiresAt) throw new Error("subscription_expiry_missing");
  if (Date.parse(expiresAt) <= Date.now()) throw new Error("subscription_expired");

  return {
    ...expected,
    storePlatform: "app_store",
    storeProductId: productId,
    storeTransactionId: transactionId,
    originalTransactionId,
    subscriptionKey: `app_store:${originalTransactionId}`,
    expiresAt,
  };
}

async function appleVerifier(): Promise<SignedDataVerifier> {
  if (!appleVerifierPromise) appleVerifierPromise = createAppleVerifier();
  return appleVerifierPromise;
}

async function createAppleVerifier(): Promise<SignedDataVerifier> {
  const environmentValue = requiredEnv("APP_STORE_ENVIRONMENT");
  const environment = environmentValue === Environment.SANDBOX
    ? Environment.SANDBOX
    : environmentValue === Environment.PRODUCTION
    ? Environment.PRODUCTION
    : (() => {
      throw new Error("invalid_app_store_environment");
    })();
  const appAppleId = environment === Environment.PRODUCTION
    ? requiredPositiveIntegerEnv("APP_STORE_APPLE_ID")
    : undefined;
  const bundleId = requiredEnv("APP_STORE_BUNDLE_ID");
  if (bundleId !== CANONICAL_APP_STORE_BUNDLE_ID) throw new Error("invalid_app_store_bundle_id");
  const rootCertificates = await Promise.all(APPLE_ROOT_CERTIFICATES.map(async (certificate) => {
    const der = Buffer.from(certificate.derBase64.replace(/\s+/g, ""), "base64");
    const actualHash = await sha256Hex(der);
    if (actualHash !== certificate.sha256) throw new Error("apple_root_certificate_integrity_failed");
    return der;
  }));
  return new SignedDataVerifier(
    rootCertificates,
    true,
    environment,
    bundleId,
    appAppleId,
  );
}

async function verifyGooglePlayPurchase(
  request: NormalizedStorePurchaseRequest,
  expected: ProductPlan,
  userId: string,
): Promise<VerifiedPurchase> {
  const packageName = requiredEnv("GOOGLE_PLAY_PACKAGE_NAME");
  if (packageName !== CANONICAL_ANDROID_APPLICATION_ID) throw new Error("invalid_google_play_package_name");
  const accessToken = await googleAccessToken();
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(request.purchaseToken)}`;
  const response = await fetch(url, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) throw new Error("google_play_verification_failed");
  const body = await response.json();
  const externalAccountIdentifiers = body?.externalAccountIdentifiers;
  const expectedObfuscatedAccountId = await sha256Hex(userId);
  const accountBinding = stringValue(externalAccountIdentifiers?.obfuscatedExternalAccountId);
  if (!(await constantTimeStringEqual(accountBinding, expectedObfuscatedAccountId)) &&
    !(await constantTimeStringEqual(accountBinding, userId))) {
    throw new Error("google_play_account_binding_mismatch");
  }
  const obfuscatedProfileId = stringValue(externalAccountIdentifiers?.obfuscatedExternalProfileId);
  if (obfuscatedProfileId &&
    !(await constantTimeStringEqual(obfuscatedProfileId, expectedObfuscatedAccountId)) &&
    !(await constantTimeStringEqual(obfuscatedProfileId, userId))) {
    throw new Error("google_play_profile_binding_mismatch");
  }
  const lineItems = Array.isArray(body.lineItems) ? body.lineItems : [];
  const matchingItem = lineItems.find((item: { productId?: unknown }) => item?.productId === request.storeProductId);
  if (!matchingItem) throw new Error("product_mismatch");
  if (!["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(String(body.subscriptionState))) {
    throw new Error("subscription_not_active");
  }
  const expiresAt = stringValue(matchingItem.expiryTime);
  if (!expiresAt || !Number.isFinite(Date.parse(expiresAt))) throw new Error("subscription_expiry_missing");
  if (Date.parse(expiresAt) <= Date.now()) throw new Error("subscription_expired");
  const providerTransactionId = stringValue(matchingItem.latestSuccessfulOrderId) || stringValue(body.latestOrderId);
  if (!providerTransactionId) throw new Error("transaction_missing");
  if (request.storeTransactionId && request.storeTransactionId !== providerTransactionId) {
    throw new Error("transaction_mismatch");
  }
  return {
    ...expected,
    storePlatform: "google_play",
    storeProductId: request.storeProductId,
    storeTransactionId: providerTransactionId,
    originalTransactionId: null,
    subscriptionKey: `google_play:${await sha256Hex(request.purchaseToken)}`,
    expiresAt,
  };
}

async function googleAccessToken(): Promise<string> {
  const now = Date.now();
  if (googleAccessTokenCache && googleAccessTokenCache.expiresAtMillis - 60_000 > now) {
    return googleAccessTokenCache.value;
  }
  if (googleAccessTokenPromise) return googleAccessTokenPromise;

  googleAccessTokenPromise = createGoogleAccessToken();
  try {
    return await googleAccessTokenPromise;
  } finally {
    googleAccessTokenPromise = null;
  }
}

async function createGoogleAccessToken(): Promise<string> {
  const serviceAccount = JSON.parse(requiredEnv("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"));
  const now = Math.floor(Date.now() / 1000);
  const privateKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const assertion = await new SignJWT({
    scope: "https://www.googleapis.com/auth/androidpublisher",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(serviceAccount.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(privateKey);
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error("google_play_auth_failed");
  const body = await response.json();
  if (typeof body.access_token !== "string") throw new Error("google_play_auth_failed");
  const expiresIn = Number(body.expires_in);
  const effectiveExpiresIn = Number.isFinite(expiresIn) && expiresIn > 60 ? expiresIn : 3600;
  googleAccessTokenCache = {
    value: body.access_token,
    expiresAtMillis: Date.now() + effectiveExpiresIn * 1000,
  };
  return body.access_token;
}

async function recordVerification(
  supabaseUrl: string,
  serviceRoleKey: string,
  userId: string,
  request: NormalizedStorePurchaseRequest,
  verified: VerifiedPurchase | null,
  status: "verified" | "failed" | "pending",
  failureReason: string | null,
  grantId: string | null,
): Promise<string> {
  const product = expectedPlanForProduct(request.storeProductId) ?? verified;
  const purchaseTokenHash = await sha256Hex(request.purchaseToken);
  const existing = await findExistingVerification(
    supabaseUrl,
    serviceRoleKey,
    request,
    purchaseTokenHash,
    verified?.originalTransactionId ?? null,
  );
  if (existing?.id) {
    if (existing.user_id !== userId) {
      if (existing.status !== "failed") throw new Error("purchase_already_claimed");
      if (status !== "verified") return existing.id;
    }
    if (status !== "verified" && existing.status === "verified") return existing.id;
    const updateResponse = await fetch(
      `${supabaseUrl}/rest/v1/store_purchase_verifications?id=eq.${encodeURIComponent(existing.id)}`,
      {
        method: "PATCH",
        headers: restHeaders(serviceRoleKey, { Prefer: "return=representation" }),
        body: JSON.stringify({
          user_id: userId,
          store_platform: request.storePlatform,
          store_product_id: verified?.storeProductId ?? request.storeProductId,
          store_transaction_id: verified?.storeTransactionId || request.storeTransactionId || null,
          original_transaction_id: verified?.originalTransactionId ?? null,
          purchase_token_hash: purchaseTokenHash,
          plan: product?.plan ?? "standard",
          billing_period: product?.billingPeriod ?? "monthly",
          status,
          failure_reason: failureReason,
          grant_id: grantId,
          expires_at: verified?.expiresAt ?? null,
          verified_at: status === "verified" ? new Date().toISOString() : null,
        }),
      },
    );
    if (!updateResponse.ok) throw new Error(`verification_record_failed:${updateResponse.status}`);
    return existing.id;
  }

  const response = await fetch(`${supabaseUrl}/rest/v1/store_purchase_verifications`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey, { Prefer: "return=representation" }),
    body: JSON.stringify({
      user_id: userId,
      store_platform: request.storePlatform,
      store_product_id: verified?.storeProductId ?? request.storeProductId,
      store_transaction_id: verified?.storeTransactionId || request.storeTransactionId || null,
      original_transaction_id: verified?.originalTransactionId ?? null,
      purchase_token_hash: purchaseTokenHash,
      plan: product?.plan ?? "standard",
      billing_period: product?.billingPeriod ?? "monthly",
      status,
      failure_reason: failureReason,
      grant_id: grantId,
      expires_at: verified?.expiresAt ?? null,
      verified_at: status === "verified" ? new Date().toISOString() : null,
    }),
  });
  if (!response.ok) throw new Error(`verification_record_failed:${await response.text()}`);
  const rows = await response.json();
  const id = rows?.[0]?.id;
  if (!id) throw new Error("verification_record_failed");
  return id;
}

async function findExistingVerification(
  supabaseUrl: string,
  serviceRoleKey: string,
  request: NormalizedStorePurchaseRequest,
  purchaseTokenHash: string,
  originalTransactionId: string | null,
): Promise<{ id: string; user_id: string; status: string } | null> {
  const byToken = await fetch(
    `${supabaseUrl}/rest/v1/store_purchase_verifications?select=id,user_id,status&store_platform=eq.${request.storePlatform}&purchase_token_hash=eq.${purchaseTokenHash}&limit=1`,
    { headers: restHeaders(serviceRoleKey) },
  );
  if (!byToken.ok) throw new Error(`verification_lookup_failed:${byToken.status}`);
  const tokenRows = await byToken.json();
  if (tokenRows?.[0]?.id) return tokenRows[0];
  if (!originalTransactionId) return null;

  const byOriginal = await fetch(
    `${supabaseUrl}/rest/v1/store_purchase_verifications?select=id,user_id,status&store_platform=eq.${request.storePlatform}&original_transaction_id=eq.${encodeURIComponent(originalTransactionId)}&status=eq.verified&limit=1`,
    { headers: restHeaders(serviceRoleKey) },
  );
  if (!byOriginal.ok) throw new Error(`verification_lookup_failed:${byOriginal.status}`);
  const originalRows = await byOriginal.json();
  return originalRows?.[0] ?? null;
}

function restHeaders(serviceRoleKey: string, extra: Record<string, string> = {}): HeadersInit {
  return {
    apikey: serviceRoleKey,
    authorization: `Bearer ${serviceRoleKey}`,
    "content-type": "application/json",
    ...extra,
  };
}

async function sha256Hex(value: string | Uint8Array): Promise<string> {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  const digest = await crypto.subtle.digest("SHA-256", buffer);
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function millisToIso(value: unknown): string | null {
  const raw = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isFinite(raw) ? new Date(raw).toISOString() : null;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function byteLength(value: string): number {
  return new TextEncoder().encode(value).byteLength;
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_env_${name}`);
  return value;
}

function requiredPositiveIntegerEnv(name: string): number {
  const value = Number(requiredEnv(name));
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`invalid_env_${name}`);
  return value;
}

export async function constantTimeStringEqual(left: string, right: string): Promise<boolean> {
  if (!left || !right) return false;
  const leftDigest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(left));
  const rightDigest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(right));
  const leftBytes = new Uint8Array(leftDigest);
  const rightBytes = new Uint8Array(rightDigest);
  let difference = 0;
  for (let index = 0; index < leftBytes.length; index += 1) difference |= leftBytes[index] ^ rightBytes[index];
  return difference === 0;
}

function publicFailureReason(message: string): string {
  if (message.startsWith("missing_env_")) return "store_verification_not_configured";
  const candidate = message.includes(":") ? message.split(":")[0] : message;
  return /^[a-z0-9_]{1,80}$/.test(candidate) ? candidate : "store_verification_failed";
}

export function shouldRecordVerificationFailure(safeReason: string): boolean {
  return safeReason !== "rate_limited" && safeReason !== "auth_required";
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const base64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}
