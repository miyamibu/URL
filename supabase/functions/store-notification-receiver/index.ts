import {
  createRemoteJWKSet,
  importPKCS8,
  type JWTPayload,
  jwtVerify,
  SignJWT,
} from "npm:jose@5.9.6";
import {
  Environment,
  SignedDataVerifier,
} from "npm:@apple/app-store-server-library@3.1.0";
import { Buffer } from "node:buffer";
import { APPLE_ROOT_CERTIFICATES } from "../verify-store-purchase/apple-root-certificates.ts";
import {
  applyStoreNotification,
  decodeBase64,
  GOOGLE_PLAY_DEVELOPER_API_SCOPE,
  GOOGLE_PLAY_ENV_NAMES,
  isoFromDateString,
  isoFromMillis,
  jsonResponse,
  MAX_NOTIFICATION_BODY_BYTES,
  readBodyText,
  recordValue,
  requiredEnv,
  safeDetail,
  sha256Hex,
  type StoreNotificationCommand,
  type StoreSubscriptionState,
  stringValue,
  validUuid,
} from "../_shared/store-notification-contract.ts";

const CANONICAL_APP_STORE_BUNDLE_ID = "com.mibu.codebridge.ios";
export const CANONICAL_GOOGLE_PLAY_PACKAGE_NAME = "jp.miyamibu.urlalbum";
const GOOGLE_ISSUERS = new Set([
  "accounts.google.com",
  "https://accounts.google.com",
]);
const GOOGLE_PUBSUB_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_OAUTH_TOKEN_URL = "https://oauth2.googleapis.com/token";
const GOOGLE_PLAY_DEVELOPER_API_BASE_URL =
  "https://androidpublisher.googleapis.com/androidpublisher/v3";

export type AppleVerifierLike = {
  verifyAndDecodeNotification(payload: string): Promise<unknown>;
  verifyAndDecodeTransaction(payload: string): Promise<unknown>;
  verifyAndDecodeRenewalInfo(payload: string): Promise<unknown>;
};

export type AppleNotificationOptions = {
  bundleId: string;
  environment: "sandbox" | "production";
  appAppleId?: number;
};

export type AppleReconciliationTransaction = {
  transactionId: string;
  originalTransactionId: string;
  productId: string;
  appAccountToken: string;
  expiresAt?: string | null;
  revokedAt?: string | null;
  purchasedAt?: string | null;
  signedAt?: string | null;
};

type GooglePushTokenVerifier = (
  token: string,
  audience: string,
) => Promise<JWTPayload>;

export type GoogleServiceAccountCredentials = {
  clientEmail: string;
  privateKey: string;
  privateKeyId?: string;
};

export type GoogleJwtProvider = (
  credentials: GoogleServiceAccountCredentials,
) => Promise<string>;

export type GooglePlayDeveloperApiOptions = {
  fetch?: typeof fetch;
  jwtProvider?: GoogleJwtProvider;
  serviceAccount?: GoogleServiceAccountCredentials | null;
  nowMillis?: number;
};

export type GooglePlaySubscriptionSnapshot = {
  providerState: string;
  productId: string;
  subscriptionState: StoreSubscriptionState;
  action: StoreNotificationCommand["action"];
  expiresAt: string | null;
  storeTransactionId: string | null;
};

type GoogleEnvelopeOptions = {
  authorizationHeader: string;
  packageName: string;
  audience: string;
  expectedServiceAccountEmail: string;
  verifyPushToken?: GooglePushTokenVerifier;
  fetch?: typeof fetch;
  jwtProvider?: GoogleJwtProvider;
  serviceAccount?: GoogleServiceAccountCredentials | null;
  nowMillis?: number;
};

let appleVerifierPromise: Promise<AppleVerifierLike> | null = null;
let googleJwks: ReturnType<typeof createRemoteJWKSet> | null = null;

export async function handleRequest(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return jsonResponse({ error: "method_not_allowed" }, 405);
  }

  let bodyText: string;
  try {
    bodyText = await readBodyText(request, MAX_NOTIFICATION_BODY_BYTES);
  } catch (error) {
    return jsonResponse({ error: publicError(error) }, 413);
  }

  let body: unknown;
  try {
    body = JSON.parse(bodyText);
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }

  try {
    const bodyRecord = recordValue(body);
    const provider = stringValue(bodyRecord.provider) ||
      inferProvider(bodyRecord);
    const command = provider === "app_store"
      ? await decodeAppleNotification(bodyRecord)
      : provider === "google_play"
      ? await decodeGoogleNotification(
        bodyRecord,
        request.headers.get("authorization") ?? "",
      )
      : (() => {
        throw new Error("provider_required");
      })();

    if (!command || command.eventType === "test") {
      return jsonResponse({
        status: "ignored",
        reason: "provider_test_notification",
      }, 202);
    }

    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
    const result = await applyStoreNotification(
      supabaseUrl,
      serviceRoleKey,
      command,
    );
    return jsonResponse({
      status: result.result,
      eventId: result.event_id,
      grantId: result.grant_id,
      grantChanged: result.grant_changed,
      reason: result.failure_reason,
    }, 202);
  } catch (error) {
    const errorCode = publicError(error);
    console.error("store notification rejected", errorCode);
    const status =
      errorCode.includes("signature") || errorCode.includes("oidc") ||
        errorCode.includes("authorization")
        ? 401
        : errorCode.includes("failed") || errorCode.includes("missing_supabase")
        ? 503
        : 400;
    return jsonResponse({ error: errorCode }, status);
  }
}

export function inferProvider(body: Record<string, unknown>): string {
  if (typeof body.signedPayload === "string") return "app_store";
  if (body.message && typeof body.message === "object") return "google_play";
  return "";
}

export async function decodeAppleNotification(
  body: Record<string, unknown>,
  verifier?: AppleVerifierLike,
  options?: AppleNotificationOptions,
): Promise<StoreNotificationCommand | null> {
  const signedPayload = stringValue(body.signedPayload);
  if (!signedPayload) throw new Error("apple_signed_payload_required");

  const resolvedOptions = options ?? appleOptionsFromEnv();
  const resolvedVerifier = verifier ?? await appleVerifier();
  const decodedNotification = recordValue(
    await resolvedVerifier.verifyAndDecodeNotification(signedPayload),
  );
  const data = recordValue(decodedNotification.data);

  const bundleId = stringValue(data.bundleId);
  if (
    bundleId !== resolvedOptions.bundleId ||
    bundleId !== CANONICAL_APP_STORE_BUNDLE_ID
  ) {
    throw new Error("apple_bundle_binding_mismatch");
  }
  const environment = stringValue(data.environment).toLowerCase();
  if (!environment || environment !== resolvedOptions.environment) {
    throw new Error("apple_environment_mismatch");
  }
  if (
    resolvedOptions.environment === "production" &&
    resolvedOptions.appAppleId !== undefined &&
    Number(data.appAppleId) !== resolvedOptions.appAppleId
  ) {
    throw new Error("apple_app_identity_mismatch");
  }

  let transaction: unknown = null;
  const signedTransactionInfo = stringValue(data.signedTransactionInfo);
  if (signedTransactionInfo) {
    transaction = await resolvedVerifier.verifyAndDecodeTransaction(
      signedTransactionInfo,
    );
  }
  let renewalInfo: unknown = null;
  const signedRenewalInfo = stringValue(data.signedRenewalInfo);
  if (signedRenewalInfo) {
    renewalInfo = await resolvedVerifier.verifyAndDecodeRenewalInfo(
      signedRenewalInfo,
    );
  }

  return normalizeAppleDecodedNotification(
    decodedNotification,
    transaction,
    renewalInfo,
    resolvedOptions,
  );
}

export function normalizeAppleDecodedNotification(
  notificationValue: unknown,
  transactionValue: unknown,
  renewalValue: unknown,
  options: AppleNotificationOptions,
): StoreNotificationCommand | null {
  const notification = recordValue(notificationValue);
  const transaction = recordValue(transactionValue);
  const renewal = recordValue(renewalValue);
  const notificationId = stringValue(notification.notificationUUID);
  const notificationType = stringValue(notification.notificationType);
  const subtype = stringValue(notification.subtype) || null;
  if (!notificationId || !notificationType) {
    throw new Error("apple_notification_identity_required");
  }
  if (notificationType === "TEST") return null;
  if (!Object.keys(transaction).length) {
    throw new Error("apple_transaction_binding_required");
  }

  const transactionId = stringValue(transaction.transactionId);
  const originalTransactionId = stringValue(transaction.originalTransactionId);
  const productId = stringValue(transaction.productId);
  const appAccountToken = stringValue(transaction.appAccountToken)
    .toLowerCase();
  if (!transactionId || !originalTransactionId || !productId) {
    throw new Error("apple_transaction_binding_required");
  }
  if (!validUuid(appAccountToken)) {
    throw new Error("apple_user_binding_required");
  }

  const occurredAt = isoFromMillis(notification.signedDate) ??
    isoFromMillis(transaction.signedDate) ??
    isoFromMillis(transaction.purchaseDate);
  if (!occurredAt) throw new Error("apple_event_time_required");

  const expiresAt = isoFromMillis(transaction.expiresDate);
  const mapping = mapAppleNotification(
    notificationType,
    subtype,
    transaction,
    renewal,
  );
  if (mapping.action === "activate" && !expiresAt) {
    throw new Error("apple_authoritative_expiry_required");
  }

  const revokeExpiry = isoFromMillis(transaction.revocationDate) ??
    expiresAt ??
    occurredAt;
  return {
    provider: "app_store",
    notificationId,
    eventId: transactionId,
    eventType: notificationType,
    subscriptionKey: `app_store:${originalTransactionId}`,
    purchaseTokenHash: null,
    storeTransactionId: transactionId,
    storeProductId: productId,
    userId: appAccountToken,
    action: mapping.action,
    subscriptionState: mapping.state,
    expiresAt: mapping.action === "revoke" ? revokeExpiry : expiresAt,
    occurredAt,
    signatureVerified: true,
    detail: safeDetail({
      notificationType,
      subtype,
      environment: options.environment,
      signedDate: notification.signedDate ?? null,
      renewalExpirationIntent: renewal.expirationIntent ?? null,
      revocationReason: transaction.revocationReason ?? null,
    }),
  };
}

export async function verifyAppleReconciliationTransaction(
  signedTransactionInfo: string,
  expected: {
    originalTransactionId: string | null;
    transactionId: string | null;
    productId: string | null;
    userId: string | null;
  },
  verifier?: AppleVerifierLike,
  options?: AppleNotificationOptions,
): Promise<AppleReconciliationTransaction> {
  if (
    !signedTransactionInfo ||
    signedTransactionInfo.length > MAX_NOTIFICATION_BODY_BYTES
  ) {
    throw new Error("apple_transaction_binding_required");
  }
  const resolvedOptions = options ?? appleOptionsFromEnv();
  const resolvedVerifier = verifier ?? await appleVerifier();
  let decoded: Record<string, unknown>;
  try {
    decoded = recordValue(
      await resolvedVerifier.verifyAndDecodeTransaction(signedTransactionInfo),
    );
  } catch {
    throw new Error("apple_transaction_signature_invalid");
  }

  const bundleId = stringValue(decoded.bundleId);
  if (bundleId !== resolvedOptions.bundleId) {
    throw new Error("apple_bundle_binding_mismatch");
  }
  const environment = stringValue(decoded.environment).toLowerCase();
  if (!environment || environment !== resolvedOptions.environment) {
    throw new Error("apple_environment_mismatch");
  }

  const transactionId = stringValue(decoded.transactionId);
  const originalTransactionId = stringValue(decoded.originalTransactionId);
  const productId = stringValue(decoded.productId);
  const appAccountToken = stringValue(decoded.appAccountToken).toLowerCase();
  if (!transactionId || !originalTransactionId || !productId) {
    throw new Error("apple_transaction_binding_required");
  }
  if (
    expected.originalTransactionId &&
    originalTransactionId !== expected.originalTransactionId
  ) {
    throw new Error("apple_transaction_binding_mismatch");
  }
  if (expected.transactionId && transactionId !== expected.transactionId) {
    throw new Error("apple_transaction_binding_mismatch");
  }
  if (expected.productId && productId !== expected.productId) {
    throw new Error("apple_product_binding_mismatch");
  }
  if (!validUuid(appAccountToken)) {
    throw new Error("apple_user_binding_required");
  }
  if (expected.userId && appAccountToken !== expected.userId.toLowerCase()) {
    throw new Error("apple_user_binding_mismatch");
  }

  return {
    transactionId,
    originalTransactionId,
    productId,
    appAccountToken,
    expiresAt: isoFromMillis(decoded.expiresDate),
    revokedAt: isoFromMillis(decoded.revocationDate),
    purchasedAt: isoFromMillis(decoded.purchaseDate),
    signedAt: isoFromMillis(decoded.signedDate),
  };
}

export function decodeGoogleNotification(
  body: Record<string, unknown>,
  authorizationHeader: string,
  options?: Omit<GoogleEnvelopeOptions, "authorizationHeader">,
): Promise<StoreNotificationCommand> {
  const packageName = options?.packageName ??
    requiredEnv(GOOGLE_PLAY_ENV_NAMES.packageName);
  validateGooglePackageName(packageName);
  const resolvedOptions: GoogleEnvelopeOptions = {
    authorizationHeader,
    packageName,
    audience: options?.audience ??
      requiredEnv(GOOGLE_PLAY_ENV_NAMES.pushAudience),
    expectedServiceAccountEmail: options?.expectedServiceAccountEmail ??
      requiredEnv(GOOGLE_PLAY_ENV_NAMES.pushServiceAccountEmail),
    verifyPushToken: options?.verifyPushToken,
    fetch: options?.fetch,
    jwtProvider: options?.jwtProvider,
    serviceAccount: options?.serviceAccount,
    nowMillis: options?.nowMillis,
  };
  return normalizeGooglePubSubEnvelope(body, resolvedOptions);
}

export async function normalizeGooglePubSubEnvelope(
  body: unknown,
  options: GoogleEnvelopeOptions,
): Promise<StoreNotificationCommand> {
  validateGooglePackageName(options.packageName);
  const token = bearerToken(options.authorizationHeader);
  const verifier = options.verifyPushToken ?? defaultVerifyGooglePushToken;
  const claims = await verifier(token, options.audience);
  validateGooglePushClaims(
    claims,
    options.audience,
    options.expectedServiceAccountEmail,
  );

  const envelope = recordValue(body);
  const message = recordValue(envelope.message);
  const notificationId = stringValue(message.messageId);
  const encodedData = stringValue(message.data);
  if (!notificationId || !encodedData) {
    throw new Error("google_notification_identity_required");
  }

  let notification: Record<string, unknown>;
  try {
    notification = recordValue(
      JSON.parse(new TextDecoder().decode(decodeBase64(encodedData))),
    );
  } catch {
    throw new Error("google_notification_payload_invalid");
  }
  if (stringValue(notification.packageName) !== options.packageName) {
    throw new Error("google_package_binding_mismatch");
  }

  const eventTimeMillis = notification.eventTimeMillis;
  const occurredAt = isoFromMillis(eventTimeMillis) ??
    isoFromDateString(message.publishTime);
  if (!occurredAt) throw new Error("google_event_time_required");

  const subscription = recordValue(notification.subscriptionNotification);
  const voided = recordValue(notification.voidedPurchaseNotification);
  const oneTime = recordValue(notification.oneTimeProductNotification);
  const pendingRefund = recordValue(
    notification.pendingRefundReviewNotification,
  );
  const test = recordValue(notification.testNotification);
  const presentKinds = [subscription, voided, oneTime, pendingRefund, test]
    .filter((value) => Object.keys(value).length > 0).length;
  if (presentKinds !== 1) throw new Error("google_notification_kind_invalid");

  if (Object.keys(test).length > 0) {
    return {
      provider: "google_play",
      notificationId,
      eventId: `google-test:${notificationId}`,
      eventType: "test",
      subscriptionKey: `google_play:test:${notificationId}`,
      purchaseTokenHash: null,
      storeTransactionId: null,
      storeProductId: null,
      userId: null,
      action: "noop",
      subscriptionState: "unsupported",
      expiresAt: null,
      occurredAt,
      signatureVerified: true,
      detail: safeDetail({ packageName: options.packageName, test: true }),
    };
  }

  if (Object.keys(subscription).length > 0) {
    const purchaseToken = stringValue(subscription.purchaseToken);
    const productId = stringValue(subscription.subscriptionId);
    const notificationType = numberValue(subscription.notificationType);
    if (!purchaseToken || !productId || notificationType === null) {
      throw new Error("google_subscription_binding_required");
    }
    const purchaseTokenHash = await sha256Hex(purchaseToken);
    const authoritative = await lookupGooglePlaySubscription(
      options.packageName,
      productId,
      purchaseToken,
      {
        fetch: options.fetch,
        jwtProvider: options.jwtProvider,
        serviceAccount: options.serviceAccount,
        nowMillis: options.nowMillis,
      },
    );
    return {
      provider: "google_play",
      notificationId,
      eventId: `google:${purchaseTokenHash}:${notificationType}:${
        String(eventTimeMillis ?? occurredAt)
      }`,
      eventType: `subscription:${notificationType}`,
      subscriptionKey: `google_play:${purchaseTokenHash}`,
      purchaseTokenHash,
      storeTransactionId: authoritative.storeTransactionId,
      storeProductId: authoritative.productId,
      userId: null,
      action: authoritative.action,
      subscriptionState: authoritative.subscriptionState,
      expiresAt: authoritative.expiresAt,
      occurredAt,
      signatureVerified: true,
      detail: safeDetail({
        packageName: options.packageName,
        subscriptionId: productId,
        notificationType,
        authoritativeState: authoritative.providerState,
        authoritativeProductId: authoritative.productId,
        authoritativeExpiryAt: authoritative.expiresAt,
        latestSuccessfulOrderId: authoritative.storeTransactionId,
        eventTimeMillis: typeof eventTimeMillis === "number" ||
            typeof eventTimeMillis === "string"
          ? Number(eventTimeMillis)
          : null,
      }),
    };
  }

  const purchaseToken = stringValue(
    voided.purchaseToken ?? oneTime.purchaseToken ??
      pendingRefund.purchaseToken,
  );
  if (!purchaseToken) throw new Error("google_purchase_binding_required");
  const purchaseTokenHash = await sha256Hex(purchaseToken);
  if (Object.keys(voided).length > 0) {
    const refundType = numberValue(voided.refundType);
    const orderId = stringValue(voided.orderId) || null;
    const fullRefund = refundType === null || refundType === 1;
    return {
      provider: "google_play",
      notificationId,
      eventId: `google:${purchaseTokenHash}:voided:${
        String(eventTimeMillis ?? occurredAt)
      }`,
      eventType: "voided_purchase",
      subscriptionKey: `google_play:${purchaseTokenHash}`,
      purchaseTokenHash,
      storeTransactionId: orderId,
      storeProductId: null,
      userId: null,
      action: fullRefund ? "revoke" : "preserve",
      subscriptionState: fullRefund ? "refunded" : "unsupported",
      expiresAt: fullRefund ? occurredAt : null,
      occurredAt,
      signatureVerified: true,
      detail: safeDetail({
        packageName: options.packageName,
        refundType,
        orderId,
      }),
    };
  }

  return {
    provider: "google_play",
    notificationId,
    eventId: `google:${purchaseTokenHash}:pending-refund:${
      String(eventTimeMillis ?? occurredAt)
    }`,
    eventType: Object.keys(oneTime).length > 0
      ? "one_time_product"
      : "pending_refund_review",
    subscriptionKey: `google_play:${purchaseTokenHash}`,
    purchaseTokenHash,
    storeTransactionId: null,
    storeProductId: stringValue(oneTime.sku) || null,
    userId: null,
    action: "preserve",
    subscriptionState: "pending",
    expiresAt: null,
    occurredAt,
    signatureVerified: true,
    detail: safeDetail({ packageName: options.packageName }),
  };
}

export async function lookupGooglePlaySubscription(
  packageName: string,
  expectedProductId: string,
  purchaseToken: string,
  options: GooglePlayDeveloperApiOptions = {},
): Promise<GooglePlaySubscriptionSnapshot> {
  if (!packageName || !expectedProductId || !purchaseToken) {
    throw new Error("google_subscription_binding_required");
  }
  validateGooglePackageName(packageName);

  if (options.serviceAccount === null) {
    throw new Error("google_play_service_account_credentials_missing");
  }
  const serviceAccount = options.serviceAccount ??
    googlePlayServiceAccountFromEnv();
  const jwtProvider = options.jwtProvider ?? defaultGoogleServiceAccountJwt;
  const requestFetch = options.fetch ?? globalThis.fetch;

  let assertion: string;
  try {
    assertion = await jwtProvider(serviceAccount);
  } catch {
    throw new Error("google_play_service_account_jwt_failed");
  }

  const accessToken = await requestGooglePlayAccessToken(
    requestFetch,
    assertion,
  );
  const endpoint =
    `${GOOGLE_PLAY_DEVELOPER_API_BASE_URL}/applications/${
      encodeURIComponent(packageName)
    }` +
    `/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;

  let response: Response;
  try {
    response = await requestFetch(endpoint, {
      method: "GET",
      headers: {
        accept: "application/json",
        authorization: `Bearer ${accessToken}`,
      },
    });
  } catch {
    throw new Error("google_play_subscription_lookup_failed");
  }
  if (!response.ok) throw new Error("google_play_subscription_lookup_failed");

  const payload = await response.json().catch(() => null);
  return normalizeGooglePlaySubscription(
    payload,
    expectedProductId,
    options.nowMillis ?? Date.now(),
  );
}

export function normalizeGooglePlaySubscription(
  value: unknown,
  expectedProductId: string,
  nowMillis = Date.now(),
): GooglePlaySubscriptionSnapshot {
  const resource = recordValue(value);
  if (
    stringValue(resource.kind) !== "androidpublisher#subscriptionPurchaseV2"
  ) {
    throw new Error("google_subscription_response_invalid");
  }

  const providerState = stringValue(resource.subscriptionState);
  const mapping = mapGooglePlaySubscriptionState(providerState);
  if (!mapping) throw new Error("google_subscription_state_invalid");

  const lineItems = Array.isArray(resource.lineItems)
    ? resource.lineItems.map(recordValue)
    : [];
  const matchingLineItem = lineItems.find(
    (lineItem) => stringValue(lineItem.productId) === expectedProductId,
  );
  if (!matchingLineItem) throw new Error("google_product_mismatch");

  const productId = stringValue(matchingLineItem.productId);
  const expiresAt = isoFromDateString(matchingLineItem.expiryTime);
  const expiryMillis = expiresAt ? Date.parse(expiresAt) : Number.NaN;
  const currentMillis = Number.isFinite(nowMillis) ? nowMillis : Date.now();
  if (providerState !== "SUBSCRIPTION_STATE_PENDING" && !expiresAt) {
    throw new Error("google_authoritative_expiry_required");
  }
  if (!Number.isNaN(expiryMillis)) {
    // Google documents ON_HOLD as having all line items expired, while
    // CANCELED can still be active until its scheduled expiry. Only states
    // that represent paid access require a future expiry here; revoke paths
    // must remain processable even when expiry is already in the past.
    const stateRequiresFutureExpiry =
      providerState === "SUBSCRIPTION_STATE_ACTIVE" ||
      providerState === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD";
    const stateRequiresPastExpiry =
      providerState === "SUBSCRIPTION_STATE_EXPIRED";
    if (stateRequiresFutureExpiry && expiryMillis <= currentMillis) {
      throw new Error("google_authoritative_expiry_invalid");
    }
    if (stateRequiresPastExpiry && expiryMillis > currentMillis) {
      throw new Error("google_authoritative_expiry_invalid");
    }
  }
  const action = mapping.action === "preserve" &&
      expiresAt !== null &&
      expiryMillis <= currentMillis
    ? "revoke"
    : mapping.action;

  return {
    providerState,
    productId,
    subscriptionState: mapping.state,
    action,
    expiresAt: expiresAt ?? null,
    storeTransactionId: stringValue(matchingLineItem.latestSuccessfulOrderId) ||
      stringValue(resource.latestOrderId) || null,
  };
}

function mapGooglePlaySubscriptionState(
  providerState: string,
):
  | {
    action: StoreNotificationCommand["action"];
    state: StoreSubscriptionState;
  }
  | null {
  switch (providerState) {
    case "SUBSCRIPTION_STATE_PENDING":
      return { action: "preserve", state: "pending" };
    case "SUBSCRIPTION_STATE_ACTIVE":
      return { action: "activate", state: "active" };
    case "SUBSCRIPTION_STATE_PAUSED":
      return { action: "revoke", state: "paused" };
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD":
      return { action: "preserve", state: "grace_period" };
    case "SUBSCRIPTION_STATE_ON_HOLD":
      return { action: "revoke", state: "on_hold" };
    case "SUBSCRIPTION_STATE_CANCELED":
      return { action: "preserve", state: "canceled" };
    case "SUBSCRIPTION_STATE_EXPIRED":
      return { action: "revoke", state: "expired" };
    case "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED":
      return { action: "revoke", state: "revoked" };
    default:
      return null;
  }
}

async function requestGooglePlayAccessToken(
  requestFetch: typeof fetch,
  assertion: string,
): Promise<string> {
  let response: Response;
  try {
    response = await requestFetch(GOOGLE_OAUTH_TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
  } catch {
    throw new Error("google_play_access_token_failed");
  }
  if (!response.ok) throw new Error("google_play_access_token_failed");
  const body = recordValue(await response.json().catch(() => null));
  const accessToken = stringValue(body.access_token);
  if (!accessToken) throw new Error("google_play_access_token_invalid");
  return accessToken;
}

function googlePlayServiceAccountFromEnv(): GoogleServiceAccountCredentials {
  return {
    clientEmail: requiredEnv(GOOGLE_PLAY_ENV_NAMES.developerClientEmail),
    privateKey: requiredEnv(GOOGLE_PLAY_ENV_NAMES.developerPrivateKey).replace(
      /\\n/g,
      "\n",
    ),
    privateKeyId: optionalEnv(GOOGLE_PLAY_ENV_NAMES.developerPrivateKeyId),
  };
}

async function defaultGoogleServiceAccountJwt(
  credentials: GoogleServiceAccountCredentials,
): Promise<string> {
  const key = await importPKCS8(credentials.privateKey, "RS256");
  const protectedHeader = credentials.privateKeyId
    ? { alg: "RS256" as const, typ: "JWT", kid: credentials.privateKeyId }
    : { alg: "RS256" as const, typ: "JWT" };
  const issuedAt = Math.floor(Date.now() / 1000);
  return await new SignJWT({ scope: GOOGLE_PLAY_DEVELOPER_API_SCOPE })
    .setProtectedHeader(protectedHeader)
    .setIssuer(credentials.clientEmail)
    .setAudience(GOOGLE_OAUTH_TOKEN_URL)
    .setIssuedAt(issuedAt)
    .setExpirationTime(issuedAt + 3600)
    .sign(key);
}

export function mapAppleNotification(
  notificationType: string,
  subtype: string | null,
  _transaction: Record<string, unknown>,
  _renewal: Record<string, unknown>,
): {
  action: StoreNotificationCommand["action"];
  state: StoreSubscriptionState;
} {
  switch (notificationType) {
    case "SUBSCRIBED":
    case "DID_RENEW":
    case "OFFER_REDEEMED":
      return { action: "activate", state: "active" };
    case "DID_FAIL_TO_RENEW":
      return subtype === "GRACE_PERIOD"
        ? { action: "preserve", state: "grace_period" }
        : { action: "preserve", state: "billing_retry" };
    case "DID_CHANGE_RENEWAL_STATUS":
      return subtype === "AUTO_RENEW_DISABLED"
        ? { action: "preserve", state: "canceled" }
        : { action: "preserve", state: "active" };
    case "EXPIRED":
      return { action: "revoke", state: "expired" };
    case "GRACE_PERIOD_EXPIRED":
      return { action: "revoke", state: "expired" };
    case "REFUND":
      return { action: "revoke", state: "refunded" };
    case "REVOKE":
      return { action: "revoke", state: "revoked" };
    case "DID_CHANGE_RENEWAL_PREF":
    case "PRICE_INCREASE":
      return { action: "preserve", state: "active" };
    default:
      return { action: "noop", state: "unsupported" };
  }
}

export function mapGoogleSubscriptionNotification(
  notificationType: number,
): {
  action: StoreNotificationCommand["action"];
  state: StoreSubscriptionState;
} {
  switch (notificationType) {
    case 12:
      return { action: "revoke", state: "revoked" };
    case 13:
      return { action: "revoke", state: "expired" };
    case 1:
    case 2:
    case 4:
    case 7:
      return { action: "preserve", state: "active" };
    case 3:
    case 18:
      return { action: "preserve", state: "canceled" };
    case 5:
      return { action: "revoke", state: "on_hold" };
    case 6:
      return { action: "preserve", state: "grace_period" };
    case 10:
      return { action: "revoke", state: "paused" };
    case 11:
      return { action: "preserve", state: "paused" };
    case 20:
      return { action: "preserve", state: "pending" };
    default:
      return { action: "noop", state: "unsupported" };
  }
}

function appleVerifier(): Promise<AppleVerifierLike> {
  if (!appleVerifierPromise) appleVerifierPromise = createAppleVerifier();
  return appleVerifierPromise;
}

async function createAppleVerifier(): Promise<AppleVerifierLike> {
  const options = appleOptionsFromEnv();
  const environment = options.environment === "sandbox"
    ? Environment.SANDBOX
    : Environment.PRODUCTION;
  const rootCertificates = await Promise.all(
    APPLE_ROOT_CERTIFICATES.map(async (certificate) => {
      const der = Buffer.from(
        certificate.derBase64.replace(/\s+/g, ""),
        "base64",
      );
      if (await sha256Hex(new Uint8Array(der)) !== certificate.sha256) {
        throw new Error("apple_root_certificate_integrity_failed");
      }
      return der;
    }),
  );
  const verifier = new SignedDataVerifier(
    rootCertificates,
    true,
    environment,
    options.bundleId,
    options.appAppleId,
  ) as unknown as AppleVerifierLike;
  return verifier;
}

function appleOptionsFromEnv(): AppleNotificationOptions {
  const bundleId = requiredEnv("APP_STORE_BUNDLE_ID");
  if (bundleId !== CANONICAL_APP_STORE_BUNDLE_ID) {
    throw new Error("invalid_app_store_bundle_id");
  }
  const environmentValue = requiredEnv("APP_STORE_ENVIRONMENT").toLowerCase();
  if (environmentValue !== "sandbox" && environmentValue !== "production") {
    throw new Error("invalid_app_store_environment");
  }
  const appAppleId = environmentValue === "production"
    ? positiveIntegerEnv("APP_STORE_APPLE_ID")
    : undefined;
  return { bundleId, environment: environmentValue, appAppleId };
}

async function defaultVerifyGooglePushToken(
  token: string,
  audience: string,
): Promise<JWTPayload> {
  if (!googleJwks) {
    googleJwks = createRemoteJWKSet(
      new URL(
        Deno.env.get(GOOGLE_PLAY_ENV_NAMES.oidcJwksUrl)?.trim() ||
          GOOGLE_PUBSUB_JWKS_URL,
      ),
    );
  }
  const verified = await jwtVerify(token, googleJwks, {
    issuer: [...GOOGLE_ISSUERS],
    audience,
  });
  return verified.payload;
}

function validateGooglePushClaims(
  claims: JWTPayload,
  audience: string,
  expectedServiceAccountEmail: string,
): void {
  const issuer = stringValue(claims.iss);
  if (!GOOGLE_ISSUERS.has(issuer)) {
    throw new Error("google_oidc_issuer_invalid");
  }
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud];
  if (!audiences.includes(audience)) {
    throw new Error("google_oidc_audience_invalid");
  }
  if (stringValue(claims.email) !== expectedServiceAccountEmail) {
    throw new Error("google_oidc_service_account_email_invalid");
  }
  if (claims.email_verified !== true) {
    throw new Error("google_oidc_email_unverified");
  }
  if (!stringValue(claims.sub)) throw new Error("google_oidc_subject_missing");
  if (
    typeof claims.exp !== "number" ||
    claims.exp <= Math.floor(Date.now() / 1000)
  ) {
    throw new Error("google_oidc_expired");
  }
}

function bearerToken(header: string): string {
  if (!header.toLowerCase().startsWith("bearer ")) {
    throw new Error("google_authorization_required");
  }
  const token = header.slice(7).trim();
  if (!token) throw new Error("google_authorization_required");
  return token;
}

function numberValue(value: unknown): number | null {
  const number = typeof value === "number"
    ? value
    : typeof value === "string"
    ? Number(value)
    : Number.NaN;
  return Number.isInteger(number) ? number : null;
}

function positiveIntegerEnv(name: string): number {
  const value = Number(requiredEnv(name));
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`invalid_${name.toLowerCase()}`);
  }
  return value;
}

function optionalEnv(name: string): string | undefined {
  const value = Deno.env.get(name)?.trim();
  return value || undefined;
}

function validateGooglePackageName(packageName: string): void {
  if (packageName !== CANONICAL_GOOGLE_PLAY_PACKAGE_NAME) {
    throw new Error("invalid_google_play_package_name");
  }
}

function publicError(error: unknown): string {
  const code = error instanceof Error
    ? error.message
    : "store_notification_failed";
  return /^[a-z0-9_:-]{1,96}$/.test(code) ? code : "store_notification_failed";
}

if (import.meta.main) Deno.serve(handleRequest);
