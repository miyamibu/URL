import { importX509, jwtVerify, SignJWT } from "npm:jose@5.9.6";

type StorePurchaseRequest = {
  storePlatform?: unknown;
  storeProductId?: unknown;
  purchaseToken?: unknown;
  storeTransactionId?: unknown;
};

type NormalizedStorePurchaseRequest = {
  storePlatform: "google_play" | "app_store";
  storeProductId: string;
  purchaseToken: string;
  storeTransactionId: string;
};

type ProductPlan = {
  plan: "standard" | "pro";
  billingPeriod: "monthly" | "yearly";
};

type VerifiedPurchase = ProductPlan & {
  storePlatform: "google_play" | "app_store";
  storeProductId: string;
  storeTransactionId: string;
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

Deno.serve(async (request) => {
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
    const expected = expectedPlanForProduct(normalized.storeProductId);
    if (!expected) {
      await recordVerification(supabaseUrl, serviceRoleKey, user.id, normalized, null, "failed", "unknown_product", null);
      return jsonResponse({ error: "unknown_product" }, 400);
    }

    const verified = normalized.storePlatform === "app_store"
      ? await verifyAppStorePurchase(normalized, expected)
      : await verifyGooglePlayPurchase(normalized, expected);

    const verificationId = await recordVerification(
      supabaseUrl,
      serviceRoleKey,
      user.id,
      normalized,
      verified,
      "verified",
      null,
      null,
    );
    const grantId = await upsertGrant(supabaseUrl, serviceRoleKey, user.id, verified);
    await attachGrant(supabaseUrl, serviceRoleKey, verificationId, grantId);

    return jsonResponse({
      status: "verified",
      verificationId,
      grantId,
      plan: verified.plan,
      billingPeriod: verified.billingPeriod,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "server_error";
    console.error("verify-store-purchase failed", message);
    if (user && normalized) {
      try {
        const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
        const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
        await recordVerification(supabaseUrl, serviceRoleKey, user.id, normalized, null, "failed", publicFailureReason(message), null);
      } catch (recordError) {
        console.error("failed to record purchase failure", recordError);
      }
    }
    const status = message === "auth_required" ? 401 : message === "invalid_request" ? 400 : 502;
    return jsonResponse({ error: publicFailureReason(message) }, status);
  }
});

async function readJson(request: Request): Promise<StorePurchaseRequest> {
  try {
    const body = await request.json();
    return body && typeof body === "object" ? body as StorePurchaseRequest : {};
  } catch {
    return {};
  }
}

function normalizeRequest(body: StorePurchaseRequest): NormalizedStorePurchaseRequest {
  const storePlatform = stringValue(body.storePlatform);
  const storeProductId = stringValue(body.storeProductId);
  const purchaseToken = stringValue(body.purchaseToken);
  const storeTransactionId = stringValue(body.storeTransactionId);
  if (!["google_play", "app_store"].includes(storePlatform) || !storeProductId || !purchaseToken) {
    throw new Error("invalid_request");
  }
  return {
    storePlatform: storePlatform as NormalizedStorePurchaseRequest["storePlatform"],
    storeProductId,
    purchaseToken,
    storeTransactionId,
  };
}

function expectedPlanForProduct(productId: string): ProductPlan | null {
  const match = /^urlsaver\.(standard|pro)\.(monthly|yearly)$/.exec(productId);
  if (!match) return null;
  return { plan: match[1] as ProductPlan["plan"], billingPeriod: match[2] as ProductPlan["billingPeriod"] };
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
): Promise<VerifiedPurchase> {
  const bundleId = requiredEnv("APP_STORE_BUNDLE_ID");
  const { protectedHeader, payload } = await jwtVerifyWithX5C(request.purchaseToken);
  if (protectedHeader.alg !== "ES256") throw new Error("app_store_invalid_algorithm");

  const productId = stringValue(payload.productId);
  const transactionId = stringValue(payload.transactionId);
  const payloadBundleId = stringValue(payload.bundleId);
  if (payloadBundleId !== bundleId) throw new Error("app_store_bundle_mismatch");
  if (!transactionId) throw new Error("transaction_missing");
  if (productId !== request.storeProductId) throw new Error("product_mismatch");
  if (request.storeTransactionId && transactionId !== request.storeTransactionId) throw new Error("transaction_mismatch");
  if (productId !== `urlsaver.${expected.plan}.${expected.billingPeriod}`) throw new Error("product_mismatch");

  const expiresAt = millisToIso(payload.expiresDate);
  if (expiresAt && Date.parse(expiresAt) <= Date.now()) throw new Error("subscription_expired");

  return {
    ...expected,
    storePlatform: "app_store",
    storeProductId: productId,
    storeTransactionId: transactionId,
    expiresAt,
  };
}

async function jwtVerifyWithX5C(jws: string) {
  const headerSegment = jws.split(".")[0];
  const protectedHeader = JSON.parse(new TextDecoder().decode(base64UrlDecode(headerSegment)));
  const certificate = protectedHeader.x5c?.[0];
  if (typeof certificate !== "string" || !certificate) throw new Error("app_store_missing_certificate");
  const pem = `-----BEGIN CERTIFICATE-----\n${certificate.match(/.{1,64}/g)?.join("\n")}\n-----END CERTIFICATE-----`;
  const key = await importX509(pem, protectedHeader.alg ?? "ES256");
  return await jwtVerify(jws, key, { algorithms: ["ES256"] });
}

async function verifyGooglePlayPurchase(
  request: NormalizedStorePurchaseRequest,
  expected: ProductPlan,
): Promise<VerifiedPurchase> {
  const packageName = requiredEnv("GOOGLE_PLAY_PACKAGE_NAME");
  const accessToken = await googleAccessToken();
  const url =
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(request.purchaseToken)}`;
  const response = await fetch(url, {
    headers: { authorization: `Bearer ${accessToken}` },
  });
  if (!response.ok) throw new Error("google_play_verification_failed");
  const body = await response.json();
  const lineItems = Array.isArray(body.lineItems) ? body.lineItems : [];
  const matchingItem = lineItems.find((item: { productId?: unknown }) => item?.productId === request.storeProductId);
  if (!matchingItem) throw new Error("product_mismatch");
  if (!["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(String(body.subscriptionState))) {
    throw new Error("subscription_not_active");
  }
  const expiresAt = stringValue(matchingItem.expiryTime) || null;
  if (expiresAt && Date.parse(expiresAt) <= Date.now()) throw new Error("subscription_expired");
  const storeTransactionId = request.storeTransactionId || stringValue(body.latestOrderId);
  if (!storeTransactionId) throw new Error("transaction_missing");
  return {
    ...expected,
    storePlatform: "google_play",
    storeProductId: request.storeProductId,
    storeTransactionId,
    expiresAt,
  };
}

async function googleAccessToken(): Promise<string> {
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
  const response = await fetch(`${supabaseUrl}/rest/v1/store_purchase_verifications`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey, { Prefer: "return=representation" }),
    body: JSON.stringify({
      user_id: userId,
      store_platform: request.storePlatform,
      store_product_id: verified?.storeProductId ?? request.storeProductId,
      store_transaction_id: verified?.storeTransactionId || request.storeTransactionId || null,
      purchase_token_hash: await sha256Hex(request.purchaseToken),
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

async function upsertGrant(
  supabaseUrl: string,
  serviceRoleKey: string,
  userId: string,
  verified: VerifiedPurchase,
): Promise<string> {
  const existing = await fetch(
    `${supabaseUrl}/rest/v1/user_entitlement_grants?select=id&source=eq.store_subscription&store_platform=eq.${verified.storePlatform}&store_transaction_id=eq.${encodeURIComponent(verified.storeTransactionId)}&limit=1`,
    {
      headers: restHeaders(serviceRoleKey),
    },
  );
  if (existing.ok) {
    const rows = await existing.json();
    if (rows?.[0]?.id) return rows[0].id;
  }

  const response = await fetch(`${supabaseUrl}/rest/v1/user_entitlement_grants`, {
    method: "POST",
    headers: restHeaders(serviceRoleKey, { Prefer: "return=representation" }),
    body: JSON.stringify({
      user_id: userId,
      plan: verified.plan,
      source: "store_subscription",
      store_platform: verified.storePlatform,
      store_transaction_id: verified.storeTransactionId,
      starts_at: new Date().toISOString(),
      expires_at: verified.expiresAt,
      status: "active",
    }),
  });
  if (!response.ok) throw new Error(`grant_create_failed:${await response.text()}`);
  const rows = await response.json();
  const id = rows?.[0]?.id;
  if (!id) throw new Error("grant_create_failed");
  return id;
}

async function attachGrant(
  supabaseUrl: string,
  serviceRoleKey: string,
  verificationId: string,
  grantId: string,
): Promise<void> {
  const response = await fetch(`${supabaseUrl}/rest/v1/store_purchase_verifications?id=eq.${verificationId}`, {
    method: "PATCH",
    headers: restHeaders(serviceRoleKey),
    body: JSON.stringify({ grant_id: grantId }),
  });
  if (!response.ok) throw new Error(`verification_grant_attach_failed:${await response.text()}`);
}

function restHeaders(serviceRoleKey: string, extra: Record<string, string> = {}): HeadersInit {
  return {
    apikey: serviceRoleKey,
    authorization: `Bearer ${serviceRoleKey}`,
    "content-type": "application/json",
    ...extra,
  };
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest)).map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function millisToIso(value: unknown): string | null {
  const raw = typeof value === "number" ? value : typeof value === "string" ? Number(value) : Number.NaN;
  return Number.isFinite(raw) ? new Date(raw).toISOString() : null;
}

function stringValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`missing_env_${name}`);
  return value;
}

function publicFailureReason(message: string): string {
  if (message.startsWith("missing_env_")) return "store_verification_not_configured";
  if (message.includes(":")) return message.split(":")[0];
  return message || "store_verification_failed";
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(status === 204 ? null : JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function base64UrlDecode(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  return Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const base64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const bytes = base64UrlDecode(base64);
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}
