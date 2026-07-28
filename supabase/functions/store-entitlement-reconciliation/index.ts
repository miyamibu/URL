import { importPKCS8, SignJWT } from "npm:jose@5.9.6";
import {
  applyStoreSubscriptionReconciliation,
  constantTimeStringEqual,
  GOOGLE_PLAY_ENV_NAMES,
  jsonResponse,
  MAX_NOTIFICATION_BODY_BYTES,
  MAX_RECONCILIATION_BATCH,
  prepareStoreSubscriptionReconciliation,
  readBodyText,
  reconcileStoreNotification,
  recordValue,
  requiredEnv,
  sha256Hex,
  type StoreNotificationAction,
  type StoreNotificationResult,
  type StoreSubscriptionState,
  stringValue,
} from "../_shared/store-notification-contract.ts";
import {
  type AppleReconciliationTransaction,
  CANONICAL_GOOGLE_PLAY_PACKAGE_NAME,
  type GooglePlaySubscriptionSnapshot,
  lookupGooglePlaySubscription,
  verifyAppleReconciliationTransaction,
} from "../store-notification-receiver/index.ts";

type ReconciliationRequest = {
  eventIds?: unknown;
  bindings?: unknown;
};

const CANONICAL_APP_STORE_BUNDLE_ID = "com.mibu.codebridge.ios";
const APPLE_SERVER_API_AUDIENCE = "appstoreconnect-v1";
const APPLE_SERVER_API_PRODUCTION_BASE_URL = "https://api.storekit.apple.com";
const APPLE_SERVER_API_SANDBOX_BASE_URL =
  "https://api.storekit-sandbox.apple.com";
const APPLE_SERVER_API_JWT_TTL_SECONDS = 300;

export type AppleReconciliationTarget = {
  originalTransactionId: string;
  productId: string;
  userId: string;
};

export type AppleSubscriptionSnapshot = AppleReconciliationTarget & {
  transactionId: string;
  status: number;
  action: StoreNotificationAction;
  subscriptionState: StoreSubscriptionState;
  expiresAt: string | null;
  observedAt: string;
  signedTransactionInfoHash: string;
};

export type AppleServerApiJwtConfig = {
  issuerId: string;
  keyId: string;
  privateKey: string;
  bundleId: string;
};

export type AppleServerApiLookupOptions = {
  fetch?: typeof fetch;
  jwtProvider?: (
    config: AppleServerApiJwtConfig,
    nowSeconds: number,
  ) => Promise<string>;
  verifyTransaction?: ReconciliationDependencies["verifyAppleBinding"];
  nowMillis?: number;
};

export type ReconciliationBinding = {
  eventId: string;
  purchaseToken?: string;
  signedTransactionInfo?: string;
  transactionId?: string;
};

export type ActiveReconciliationResult =
  & Omit<StoreNotificationResult, "result">
  & {
    result: StoreNotificationResult["result"] | "not_verified";
    mode: "google_current_state" | "apple_current_state";
    verification: "verified" | "not_verified";
  };

export type ReconciliationDependencies = {
  prepareTarget?: typeof prepareStoreSubscriptionReconciliation;
  applySnapshot?: typeof applyStoreSubscriptionReconciliation;
  lookupGoogle?: (
    packageName: string,
    productId: string,
    purchaseToken: string,
  ) => Promise<GooglePlaySubscriptionSnapshot>;
  verifyAppleBinding?: (
    signedTransactionInfo: string,
    expected: {
      originalTransactionId: string | null;
      transactionId: string | null;
      productId: string | null;
      userId: string | null;
    },
  ) => Promise<AppleReconciliationTransaction>;
  lookupApple?: (
    anyTransactionId: string,
    target: AppleReconciliationTarget,
    options?: AppleServerApiLookupOptions,
  ) => Promise<AppleSubscriptionSnapshot>;
};

export async function handleRequest(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  let expectedSecret: string;
  try {
    expectedSecret = requiredSecret();
  } catch {
    return jsonResponse({ error: "reconciliation_unavailable" }, 503);
  }
  const suppliedSecret = request.headers.get("x-store-reconciliation-secret") ??
    "";
  if (!constantTimeStringEqual(suppliedSecret, expectedSecret)) {
    return jsonResponse({ error: "authorization_required" }, 401);
  }

  let bodyText: string;
  try {
    bodyText = await readBodyText(request, MAX_NOTIFICATION_BODY_BYTES);
  } catch {
    return jsonResponse({ error: "payload_too_large" }, 413);
  }

  let body: ReconciliationRequest;
  try {
    body = recordValue(JSON.parse(bodyText)) as ReconciliationRequest;
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }

  const eventIds = normalizeEventIds(body.eventIds);
  if (!eventIds.length) {
    return jsonResponse({ error: "event_ids_required" }, 400);
  }

  let bindings: Map<string, ReconciliationBinding>;
  try {
    bindings = normalizeBindings(body.bindings, eventIds);
  } catch (error) {
    const code =
      error instanceof Error && /^[a-z0-9_:-]{1,96}$/.test(error.message)
        ? error.message
        : "invalid_reconciliation_binding";
    return jsonResponse({ error: code }, 400);
  }

  try {
    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
    const results = [];
    for (const eventId of eventIds) {
      const binding = bindings.get(eventId);
      results.push(
        binding
          ? await reconcileCurrentStoreState(
            supabaseUrl,
            serviceRoleKey,
            binding,
          )
          : await reconcileStoreNotification(
            supabaseUrl,
            serviceRoleKey,
            eventId,
          ),
      );
    }
    return jsonResponse({ status: "completed", results }, 200);
  } catch (error) {
    const code =
      error instanceof Error && /^[a-z0-9_:-]{1,96}$/.test(error.message)
        ? error.message
        : "reconciliation_failed";
    console.error("store entitlement reconciliation failed", code);
    return jsonResponse({ error: code }, 503);
  }
}

export function normalizeEventIds(value: unknown): string[] {
  const values = Array.isArray(value)
    ? value
    : typeof value === "string"
    ? [value]
    : [];
  const unique = new Set<string>();
  for (const item of values) {
    const eventId = stringValue(item);
    if (
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
        .test(eventId)
    ) {
      unique.add(eventId.toLowerCase());
    }
    if (unique.size >= MAX_RECONCILIATION_BATCH) break;
  }
  return [...unique];
}

export function normalizeBindings(
  value: unknown,
  eventIds: readonly string[] = [],
): Map<string, ReconciliationBinding> {
  if (value === undefined || value === null) return new Map();
  if (!Array.isArray(value)) throw new Error("reconciliation_bindings_invalid");

  const allowed = new Set(eventIds);
  const bindings = new Map<string, ReconciliationBinding>();
  for (const item of value) {
    const record = recordValue(item);
    const eventId = normalizeEventIds(record.eventId)[0];
    if (!eventId || !allowed.has(eventId)) {
      throw new Error("reconciliation_binding_event_invalid");
    }
    const purchaseToken = stringValue(record.purchaseToken);
    const signedTransactionInfo = stringValue(record.signedTransactionInfo);
    const transactionId = stringValue(record.transactionId);
    if (purchaseToken && (signedTransactionInfo || transactionId)) {
      throw new Error("reconciliation_binding_provider_ambiguous");
    }
    if (
      purchaseToken.length > 4096 ||
      signedTransactionInfo.length > MAX_NOTIFICATION_BODY_BYTES ||
      transactionId.length > 256
    ) {
      throw new Error("reconciliation_binding_too_large");
    }
    if (!purchaseToken && !signedTransactionInfo && !transactionId) {
      throw new Error("reconciliation_binding_value_required");
    }
    const binding: ReconciliationBinding = { eventId };
    if (purchaseToken) binding.purchaseToken = purchaseToken;
    if (signedTransactionInfo) {
      binding.signedTransactionInfo = signedTransactionInfo;
    }
    if (transactionId) binding.transactionId = transactionId;

    const previous = bindings.get(eventId);
    if (previous && JSON.stringify(previous) !== JSON.stringify(binding)) {
      throw new Error("reconciliation_binding_duplicate");
    }
    bindings.set(eventId, binding);
    if (bindings.size >= MAX_RECONCILIATION_BATCH) break;
  }
  return bindings;
}

export function reconcileCurrentStoreState(
  supabaseUrl: string,
  serviceRoleKey: string,
  binding: ReconciliationBinding,
  dependencies: ReconciliationDependencies = {},
): Promise<ActiveReconciliationResult> {
  if (binding.purchaseToken) {
    return reconcileGoogleCurrentState(
      supabaseUrl,
      serviceRoleKey,
      binding,
      dependencies,
    );
  }
  return reconcileAppleCurrentState(
    supabaseUrl,
    serviceRoleKey,
    binding,
    dependencies,
  );
}

async function reconcileGoogleCurrentState(
  supabaseUrl: string,
  serviceRoleKey: string,
  binding: ReconciliationBinding,
  dependencies: ReconciliationDependencies,
): Promise<ActiveReconciliationResult> {
  const purchaseToken = binding.purchaseToken;
  if (!purchaseToken) throw new Error("google_purchase_token_binding_required");
  const purchaseTokenHash = await sha256Hex(purchaseToken);
  const prepareTarget = dependencies.prepareTarget ??
    prepareStoreSubscriptionReconciliation;
  const target = await prepareTarget(
    supabaseUrl,
    serviceRoleKey,
    binding.eventId,
    {
      purchaseTokenHash,
    },
  );
  if (target.provider !== "google_play") {
    throw new Error("google_reconciliation_provider_mismatch");
  }
  if (
    target.binding_status !== "verified" ||
    target.purchase_token_hash !== purchaseTokenHash
  ) {
    throw new Error("google_purchase_token_binding_mismatch");
  }
  const productId = target.store_product_id;
  if (!productId) throw new Error("google_product_binding_required");

  const packageName = requiredGooglePackageName();
  const lookup = dependencies.lookupGoogle ?? (
    (name: string, product: string, token: string) =>
      lookupGooglePlaySubscription(name, product, token)
  );
  const snapshot = await lookup(packageName, productId, purchaseToken);
  if (snapshot.productId !== productId) {
    throw new Error("google_product_binding_mismatch");
  }

  const applySnapshot = dependencies.applySnapshot ??
    applyStoreSubscriptionReconciliation;
  const result = await applySnapshot(supabaseUrl, serviceRoleKey, {
    eventId: target.event_id,
    purchaseTokenHash,
    storeTransactionId: snapshot.storeTransactionId,
    storeProductId: snapshot.productId,
    action: snapshot.action,
    subscriptionState: snapshot.subscriptionState,
    expiresAt: snapshot.expiresAt,
    observedAt: new Date().toISOString(),
    signedTransactionInfoHash: null,
  });
  return {
    ...result,
    mode: "google_current_state",
    verification: "verified",
  };
}

async function reconcileAppleCurrentState(
  supabaseUrl: string,
  serviceRoleKey: string,
  binding: ReconciliationBinding,
  dependencies: ReconciliationDependencies,
): Promise<ActiveReconciliationResult> {
  const prepareTarget = dependencies.prepareTarget ??
    prepareStoreSubscriptionReconciliation;
  const target = await prepareTarget(
    supabaseUrl,
    serviceRoleKey,
    binding.eventId,
    {
      storeTransactionId: binding.transactionId ?? null,
    },
  );
  if (target.provider !== "app_store") {
    throw new Error("apple_reconciliation_provider_mismatch");
  }
  if (target.binding_status !== "verified") {
    throw new Error(
      target.failure_reason ?? "apple_reconciliation_binding_not_verified",
    );
  }

  const originalTransactionId = target.original_transaction_id;
  const productId = target.store_product_id;
  const userId = target.user_id;
  if (!originalTransactionId || !productId || !userId) {
    throw new Error("apple_reconciliation_binding_incomplete");
  }

  const verifyBinding = dependencies.verifyAppleBinding ?? (
    (signedTransactionInfo: string, expected: {
      originalTransactionId: string | null;
      transactionId: string | null;
      productId: string | null;
      userId: string | null;
    }) => verifyAppleReconciliationTransaction(signedTransactionInfo, expected)
  );

  if (binding.signedTransactionInfo) {
    await verifyBinding(binding.signedTransactionInfo, {
      originalTransactionId,
      transactionId: binding.transactionId ?? null,
      productId,
      userId,
    });
  }

  const anyTransactionId = binding.transactionId ?? target.store_transaction_id;
  if (!anyTransactionId) throw new Error("apple_transaction_id_required");

  const lookupApple = dependencies.lookupApple ?? lookupAppleSubscription;
  const snapshot = await lookupApple(anyTransactionId, {
    originalTransactionId,
    productId,
    userId,
  }, { verifyTransaction: verifyBinding });

  if (
    snapshot.originalTransactionId !== originalTransactionId ||
    snapshot.productId !== productId ||
    snapshot.userId.toLowerCase() !== userId.toLowerCase()
  ) {
    throw new Error("apple_reconciliation_snapshot_binding_mismatch");
  }

  const applySnapshot = dependencies.applySnapshot ??
    applyStoreSubscriptionReconciliation;
  const result = await applySnapshot(supabaseUrl, serviceRoleKey, {
    eventId: target.event_id,
    purchaseTokenHash: null,
    storeTransactionId: snapshot.transactionId,
    storeProductId: snapshot.productId,
    action: snapshot.action,
    subscriptionState: snapshot.subscriptionState,
    expiresAt: snapshot.expiresAt,
    observedAt: snapshot.observedAt,
    signedTransactionInfoHash: snapshot.signedTransactionInfoHash,
  });

  return {
    ...result,
    mode: "apple_current_state",
    verification: "verified",
  };
}

export async function lookupAppleSubscription(
  anyTransactionId: string,
  target: AppleReconciliationTarget,
  options: AppleServerApiLookupOptions = {},
): Promise<AppleSubscriptionSnapshot> {
  if (
    !anyTransactionId || !target.originalTransactionId || !target.productId ||
    !target.userId
  ) {
    throw new Error("apple_subscription_binding_required");
  }

  const config = requiredAppleServerApiConfig();
  const requestFetch = options.fetch ?? globalThis.fetch;
  const nowMillis = Number.isFinite(options.nowMillis)
    ? options.nowMillis as number
    : Date.now();
  const nowSeconds = Math.floor(nowMillis / 1000);
  const jwtProvider = options.jwtProvider ?? defaultAppleServerApiJwt;
  let authorizationToken: string;
  try {
    authorizationToken = await jwtProvider(config, nowSeconds);
  } catch {
    throw new Error("apple_server_api_jwt_failed");
  }

  const endpoint = `${
    appleServerApiBaseUrl(config.environment)
  }/inApps/v1/subscriptions/${encodeURIComponent(anyTransactionId)}`;
  let response: Response;
  try {
    response = await requestFetch(endpoint, {
      method: "GET",
      headers: {
        accept: "application/json",
        authorization: `Bearer ${authorizationToken}`,
      },
    });
  } catch {
    throw new Error("apple_server_api_request_failed");
  }
  if (!response.ok) throw new Error("apple_server_api_request_failed");

  const payload = recordValue(await response.json().catch(() => null));
  validateAppleServerApiIdentity(payload, config);

  const verifyTransaction = options.verifyTransaction ?? (
    (signedTransactionInfo: string, expected: {
      originalTransactionId: string | null;
      transactionId: string | null;
      productId: string | null;
      userId: string | null;
    }) => verifyAppleReconciliationTransaction(signedTransactionInfo, expected)
  );
  const candidates: Array<{
    status: number;
    transaction: AppleReconciliationTransaction;
    signedTransactionInfoHash: string;
  }> = [];
  const groups = Array.isArray(payload.data) ? payload.data : [];
  for (const groupValue of groups) {
    const group = recordValue(groupValue);
    const transactions = Array.isArray(group.lastTransactions)
      ? group.lastTransactions
      : [];
    for (const transactionValue of transactions) {
      const transactionRecord = recordValue(transactionValue);
      const status = positiveIntegerValue(transactionRecord.status);
      if (status === null || status < 1 || status > 5) continue;
      const signedTransactionInfo = stringValue(
        transactionRecord.signedTransactionInfo,
      );
      if (!signedTransactionInfo) continue;

      let transaction: AppleReconciliationTransaction;
      try {
        transaction = await verifyTransaction(signedTransactionInfo, {
          originalTransactionId: null,
          transactionId: null,
          productId: null,
          userId: null,
        });
      } catch (error) {
        if (isAppleBindingMismatch(error)) continue;
        throw error;
      }
      if (
        transaction.originalTransactionId !== target.originalTransactionId ||
        transaction.productId !== target.productId ||
        transaction.appAccountToken.toLowerCase() !==
          target.userId.toLowerCase()
      ) continue;
      candidates.push({
        status,
        transaction,
        signedTransactionInfoHash: await sha256Hex(signedTransactionInfo),
      });
    }
  }
  if (!candidates.length) {
    throw new Error("apple_subscription_binding_not_found");
  }

  candidates.sort((left, right) => {
    const rightTime = appleTransactionSortTime(right.transaction);
    const leftTime = appleTransactionSortTime(left.transaction);
    if (rightTime !== leftTime) return rightTime - leftTime;
    return right.transaction.transactionId.localeCompare(
      left.transaction.transactionId,
    );
  });
  const selected = candidates[0];
  const mapping = mapAppleSubscriptionStatus(
    selected.status,
    selected.transaction.expiresAt ?? null,
    selected.transaction.revokedAt ?? null,
    nowMillis,
  );
  const observedAt = selected.transaction.signedAt ??
    selected.transaction.purchasedAt ??
    new Date(nowMillis).toISOString();
  return {
    originalTransactionId: selected.transaction.originalTransactionId,
    productId: selected.transaction.productId,
    userId: selected.transaction.appAccountToken,
    transactionId: selected.transaction.transactionId,
    status: selected.status,
    action: mapping.action,
    subscriptionState: mapping.state,
    expiresAt: mapping.eventExpiresAt,
    observedAt,
    signedTransactionInfoHash: selected.signedTransactionInfoHash,
  };
}

export function mapAppleSubscriptionStatus(
  status: number,
  expiresAt: string | null,
  revokedAt: string | null,
  nowMillis = Date.now(),
): {
  action: StoreNotificationAction;
  state: StoreSubscriptionState;
  eventExpiresAt: string | null;
} {
  const expiryMillis = parseOptionalDate(expiresAt);
  const revokedMillis = parseOptionalDate(revokedAt);
  if (expiresAt !== null && expiryMillis === null) {
    throw new Error("apple_authoritative_expiry_invalid");
  }
  if (revokedAt !== null && revokedMillis === null) {
    throw new Error("apple_revocation_date_invalid");
  }

  if (revokedMillis !== null) {
    return { action: "revoke", state: "revoked", eventExpiresAt: revokedAt };
  }
  switch (status) {
    case 1:
      if (expiryMillis === null || expiryMillis <= nowMillis) {
        throw new Error("apple_authoritative_expiry_invalid");
      }
      return { action: "activate", state: "active", eventExpiresAt: expiresAt };
    case 2:
      return { action: "revoke", state: "expired", eventExpiresAt: expiresAt };
    case 3:
      return expiryMillis !== null && expiryMillis > nowMillis
        ? {
          action: "preserve",
          state: "billing_retry",
          eventExpiresAt: expiresAt,
        }
        : {
          action: "revoke",
          state: "billing_retry",
          eventExpiresAt: expiresAt,
        };
    case 4:
      return expiryMillis !== null && expiryMillis > nowMillis
        ? {
          action: "preserve",
          state: "grace_period",
          eventExpiresAt: expiresAt,
        }
        : {
          action: "revoke",
          state: "grace_period",
          eventExpiresAt: expiresAt,
        };
    case 5:
      return { action: "revoke", state: "revoked", eventExpiresAt: expiresAt };
    default:
      throw new Error("apple_subscription_status_invalid");
  }
}

export async function createAppleServerApiJwt(
  config: AppleServerApiJwtConfig,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<string> {
  if (
    !config.issuerId || !config.keyId || !config.privateKey || !config.bundleId
  ) {
    throw new Error("apple_server_api_credentials_missing");
  }
  const key = await importPKCS8(
    config.privateKey.replace(/\\n/g, "\n"),
    "ES256",
  );
  return await new SignJWT({ bid: config.bundleId })
    .setProtectedHeader({ alg: "ES256", kid: config.keyId, typ: "JWT" })
    .setIssuer(config.issuerId)
    .setAudience(APPLE_SERVER_API_AUDIENCE)
    .setIssuedAt(nowSeconds)
    .setExpirationTime(nowSeconds + APPLE_SERVER_API_JWT_TTL_SECONDS)
    .sign(key);
}

function requiredAppleServerApiConfig(): AppleServerApiJwtConfig & {
  environment: "sandbox" | "production";
  appAppleId: number;
} {
  const bundleId = requiredEnv("APP_STORE_BUNDLE_ID");
  if (bundleId !== CANONICAL_APP_STORE_BUNDLE_ID) {
    throw new Error("invalid_app_store_bundle_id");
  }
  const environmentValue = requiredEnv("APP_STORE_ENVIRONMENT").toLowerCase();
  if (environmentValue !== "sandbox" && environmentValue !== "production") {
    throw new Error("invalid_app_store_environment");
  }
  return {
    bundleId,
    environment: environmentValue,
    appAppleId: requiredPositiveIntegerEnv("APP_STORE_APPLE_ID"),
    issuerId: requiredEnv("APP_STORE_SERVER_API_ISSUER_ID"),
    keyId: requiredEnv("APP_STORE_SERVER_API_KEY_ID"),
    privateKey: requiredEnv("APP_STORE_SERVER_API_PRIVATE_KEY"),
  };
}

function appleServerApiBaseUrl(environment: "sandbox" | "production"): string {
  return environment === "sandbox"
    ? APPLE_SERVER_API_SANDBOX_BASE_URL
    : APPLE_SERVER_API_PRODUCTION_BASE_URL;
}

function validateAppleServerApiIdentity(
  payload: Record<string, unknown>,
  config: {
    bundleId: string;
    environment: "sandbox" | "production";
    appAppleId: number;
  },
): void {
  if (stringValue(payload.bundleId) !== config.bundleId) {
    throw new Error("apple_bundle_binding_mismatch");
  }
  if (stringValue(payload.environment).toLowerCase() !== config.environment) {
    throw new Error("apple_environment_mismatch");
  }
  if (positiveIntegerValue(payload.appAppleId) !== config.appAppleId) {
    throw new Error("apple_app_identity_mismatch");
  }
}

function defaultAppleServerApiJwt(
  config: AppleServerApiJwtConfig,
  nowSeconds: number,
): Promise<string> {
  return createAppleServerApiJwt(config, nowSeconds);
}

function positiveIntegerValue(value: unknown): number | null {
  const numberValue = typeof value === "number"
    ? value
    : typeof value === "string" && value.trim()
    ? Number(value)
    : Number.NaN;
  return Number.isSafeInteger(numberValue) && numberValue > 0
    ? numberValue
    : null;
}

function requiredPositiveIntegerEnv(name: string): number {
  const value = positiveIntegerValue(requiredEnv(name));
  if (value === null) throw new Error(`invalid_${name.toLowerCase()}`);
  return value;
}

function parseOptionalDate(value: string | null): number | null {
  if (value === null) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function appleTransactionSortTime(
  transaction: AppleReconciliationTransaction,
): number {
  const values = [
    transaction.signedAt,
    transaction.purchasedAt,
    transaction.expiresAt,
  ];
  for (const value of values) {
    const parsed = value ? Date.parse(value) : Number.NaN;
    if (Number.isFinite(parsed)) return parsed;
  }
  return 0;
}

function isAppleBindingMismatch(error: unknown): boolean {
  const message = error instanceof Error ? error.message : "";
  return /apple_(?:bundle_binding_mismatch|environment_mismatch|app_identity_mismatch|transaction_binding_mismatch|product_binding_mismatch|user_binding_mismatch|user_binding_required|transaction_binding_required)$/
    .test(message);
}

function requiredGooglePackageName(): string {
  const packageName = requiredEnv(GOOGLE_PLAY_ENV_NAMES.packageName);
  if (packageName !== CANONICAL_GOOGLE_PLAY_PACKAGE_NAME) {
    throw new Error("google_package_configuration_not_verified");
  }
  return packageName;
}

function requiredSecret(): string {
  const value = requiredEnv("STORE_RECONCILIATION_SECRET");
  if (value.length < 32) throw new Error("invalid_store_reconciliation_secret");
  return value;
}

if (import.meta.main) Deno.serve(handleRequest);
