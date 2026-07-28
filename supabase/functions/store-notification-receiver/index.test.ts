import {
  decodeAppleNotification,
  lookupGooglePlaySubscription,
  mapAppleNotification,
  mapGoogleSubscriptionNotification,
  normalizeGooglePubSubEnvelope,
} from "./index.ts";
import { sha256Hex } from "../_shared/store-notification-contract.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
  }
}

async function assertRejects(action: () => unknown | Promise<unknown>, expectedCode: string): Promise<void> {
  try {
    await action();
  } catch (error) {
    assert(error instanceof Error, "expected Error");
    assertEquals(error.message, expectedCode, "rejection code");
    return;
  }
  throw new Error(`expected rejection: ${expectedCode}`);
}

function base64Json(value: unknown): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function googleSubscriptionResponse(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    kind: "androidpublisher#subscriptionPurchaseV2",
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    lineItems: [{
      productId: "urlsaver.standard.monthly",
      expiryTime: "2026-08-27T00:00:00.000Z",
      latestSuccessfulOrderId: "GPA.0000-0000-0000-00000",
    }],
    ...overrides,
  };
}

function googleOptions(
  apiResponse: Record<string, unknown> = googleSubscriptionResponse(),
  apiStatus = 200,
) {
  const calls: string[] = [];
  return {
    authorizationHeader: "Bearer fixture-token",
    packageName: "jp.miyamibu.urlalbum",
    audience: "https://staging.example.test/store-notification",
    expectedServiceAccountEmail: "pubsub-push@example.iam.gserviceaccount.com",
    verifyPushToken: async () => ({
      iss: "https://accounts.google.com",
      aud: "https://staging.example.test/store-notification",
      email: "pubsub-push@example.iam.gserviceaccount.com",
      email_verified: true,
      sub: "pubsub-subject",
      exp: Math.floor(Date.now() / 1000) + 300,
    }),
    serviceAccount: {
      clientEmail: "play-api@example.iam.gserviceaccount.com",
      privateKey: "fixture-private-key",
      privateKeyId: "fixture-key-id",
    },
    jwtProvider: async () => "fixture-service-account-assertion",
    fetch: async (input: RequestInfo | URL): Promise<Response> => {
      const url = String(input);
      calls.push(url);
      if (url === "https://oauth2.googleapis.com/token") {
        return new Response(JSON.stringify({ access_token: "fixture-access-token" }), {
          status: 200,
          headers: { "content-type": "application/json" },
        });
      }
      return new Response(JSON.stringify(apiResponse), {
        status: apiStatus,
        headers: { "content-type": "application/json" },
      });
    },
    nowMillis: Date.parse("2026-07-27T00:00:00.000Z"),
    calls,
  };
}

function googleEnvelope(notification: Record<string, unknown>, messageId = "google-message-001") {
  return {
    message: {
      messageId,
      publishTime: "2026-07-27T00:00:00.000Z",
      data: base64Json(notification),
    },
    subscription: "projects/test/subscriptions/store-notifications",
  };
}

Deno.test("Apple notification adapter verifies app identity and maps renewal", async () => {
  const transaction = {
    transactionId: "apple-transaction-001",
    originalTransactionId: "apple-original-001",
    productId: "urlsaver.standard.monthly",
    appAccountToken: "00000000-0000-4000-8000-000000000001",
    signedDate: 1782432000000,
    expiresDate: 1785110400000,
  };
  const notification = {
    notificationUUID: "00000000-0000-4000-8000-000000000002",
    notificationType: "DID_RENEW",
    signedDate: 1782432000000,
    data: {
      bundleId: "com.mibu.codebridge.ios",
      environment: "Sandbox",
      signedTransactionInfo: "fixture-signed-transaction",
      signedRenewalInfo: "fixture-signed-renewal",
    },
  };
  const fakeVerifier = {
    verifyAndDecodeNotification: async () => notification,
    verifyAndDecodeTransaction: async () => transaction,
    verifyAndDecodeRenewalInfo: async () => ({}),
  };

  const command = await decodeAppleNotification(
    { signedPayload: "fixture-signed-payload" },
    fakeVerifier,
    { bundleId: "com.mibu.codebridge.ios", environment: "sandbox" },
  );

  assert(command !== null, "renewal should produce a command");
  assertEquals(command.provider, "app_store", "Apple provider");
  assertEquals(command.subscriptionKey, "app_store:apple-original-001", "Apple subscription binding");
  assertEquals(command.userId, "00000000-0000-4000-8000-000000000001", "Apple account binding");
  assertEquals(command.action, "activate", "renewal action");
  assertEquals(command.subscriptionState, "active", "renewal state");
});

Deno.test("Apple notification adapter fails closed without user binding", async () => {
  await assertRejects(
    () => decodeAppleNotification(
      { signedPayload: "fixture" },
      {
        verifyAndDecodeNotification: async () => ({
          notificationUUID: "00000000-0000-4000-8000-000000000010",
          notificationType: "DID_RENEW",
          signedDate: 1782432000000,
          data: {
            bundleId: "com.mibu.codebridge.ios",
            environment: "Sandbox",
            signedTransactionInfo: "fixture-signed-transaction",
          },
        }),
        verifyAndDecodeTransaction: async () => ({
          transactionId: "apple-transaction-010",
          originalTransactionId: "apple-original-010",
          productId: "urlsaver.standard.monthly",
          signedDate: 1782432000000,
          expiresDate: 1785110400000,
        }),
        verifyAndDecodeRenewalInfo: async () => ({}),
      },
      { bundleId: "com.mibu.codebridge.ios", environment: "sandbox" },
    ),
    "apple_user_binding_required",
  );
});

Deno.test("Apple notification adapter fails closed when environment binding is absent", async () => {
  await assertRejects(
    () => decodeAppleNotification(
      { signedPayload: "fixture" },
      {
        verifyAndDecodeNotification: async () => ({
          notificationUUID: "00000000-0000-4000-8000-000000000011",
          notificationType: "DID_RENEW",
          signedDate: 1782432000000,
          data: {
            bundleId: "com.mibu.codebridge.ios",
            signedTransactionInfo: "fixture-signed-transaction",
          },
        }),
        verifyAndDecodeTransaction: async () => ({
          transactionId: "apple-transaction-011",
          originalTransactionId: "apple-original-011",
          productId: "urlsaver.standard.monthly",
          appAccountToken: "00000000-0000-4000-8000-000000000011",
          signedDate: 1782432000000,
          expiresDate: 1785110400000,
        }),
        verifyAndDecodeRenewalInfo: async () => ({}),
      },
      { bundleId: "com.mibu.codebridge.ios", environment: "sandbox" },
    ),
    "apple_environment_mismatch",
  );
});

Deno.test("Apple refund and billing mappings do not invent a regrant", () => {
  assertEquals(mapAppleNotification("REFUND", null, {}, {}).action, "revoke", "Apple refund action");
  assertEquals(mapAppleNotification("REFUND", null, {}, {}).state, "refunded", "Apple refund state");
  assertEquals(mapAppleNotification("DID_FAIL_TO_RENEW", "GRACE_PERIOD", {}, {}).action, "preserve", "Apple grace action");
  assertEquals(mapAppleNotification("DID_FAIL_TO_RENEW", "GRACE_PERIOD", {}, {}).state, "grace_period", "Apple grace state");
  assertEquals(mapAppleNotification("UNKNOWN_PROVIDER_EVENT", null, {}, {}).action, "noop", "unknown action");
});

Deno.test("Google RTDN validates OIDC claims, package, product, and token binding", async () => {
  const command = await normalizeGooglePubSubEnvelope(
    googleEnvelope({
      version: "1.0",
      packageName: "jp.miyamibu.urlalbum",
      eventTimeMillis: "1782432000000",
      subscriptionNotification: {
        version: "1.0",
        notificationType: 2,
        purchaseToken: "fixture-purchase-token",
        subscriptionId: "urlsaver.standard.monthly",
      },
    }),
    googleOptions(),
  );
  assertEquals(command.provider, "google_play", "Google provider");
  assertEquals(command.storeProductId, "urlsaver.standard.monthly", "Google product binding");
  assertEquals(command.subscriptionState, "active", "Google renewal state");
  assertEquals(command.action, "activate", "authoritative Google state activates the grant");
  assertEquals(command.expiresAt, "2026-08-27T00:00:00.000Z", "authoritative expiry");
  assertEquals(command.storeTransactionId, "GPA.0000-0000-0000-00000", "authoritative order ID");
  assertEquals(command.purchaseTokenHash, await sha256Hex("fixture-purchase-token"), "Google token hash");
  assert(!JSON.stringify(command).includes("fixture-purchase-token"), "raw purchase token must not enter command");
});

Deno.test("Google paused and on-hold authoritative states revoke the grant", async () => {
  for (const [providerState, expectedState, expiryTime] of [
    ["SUBSCRIPTION_STATE_PAUSED", "paused", "2026-08-27T00:00:00.000Z"],
    ["SUBSCRIPTION_STATE_ON_HOLD", "on_hold", "2026-07-26T00:00:00.000Z"],
  ] as const) {
    const command = await normalizeGooglePubSubEnvelope(
      googleEnvelope({
        version: "1.0",
        packageName: "jp.miyamibu.urlalbum",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          version: "1.0",
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }, `google-message-${providerState}`),
      googleOptions(googleSubscriptionResponse({
        subscriptionState: providerState,
        lineItems: [{
          productId: "urlsaver.standard.monthly",
          expiryTime,
          latestSuccessfulOrderId: "GPA.0000-0000-0000-00000",
        }],
      })),
    );
    assertEquals(command.action, "revoke", `${providerState} revokes the grant`);
    assertEquals(command.subscriptionState, expectedState, `${providerState} state`);
  }
});

Deno.test("Google canceled state preserves only until authoritative expiry", async () => {
  const command = await normalizeGooglePubSubEnvelope(
    googleEnvelope({
      version: "1.0",
      packageName: "jp.miyamibu.urlalbum",
      eventTimeMillis: "1782432000000",
      subscriptionNotification: {
        version: "1.0",
        notificationType: 3,
        purchaseToken: "fixture-purchase-token",
        subscriptionId: "urlsaver.standard.monthly",
      },
    }),
    googleOptions(googleSubscriptionResponse({
      subscriptionState: "SUBSCRIPTION_STATE_CANCELED",
      lineItems: [{
        productId: "urlsaver.standard.monthly",
        expiryTime: "2026-07-26T00:00:00.000Z",
        latestSuccessfulOrderId: "GPA.0000-0000-0000-00000",
      }],
    })),
  );
  assertEquals(command.action, "revoke", "expired canceled state revokes the grant");
  assertEquals(command.subscriptionState, "canceled", "canceled state is retained");
});

Deno.test("Google RTDN requires the configured Pub/Sub service account identity", async () => {
  const mismatchedEmail = googleOptions();
  mismatchedEmail.verifyPushToken = async () => ({
    iss: "https://accounts.google.com",
    aud: mismatchedEmail.audience,
    email: "another-service-account@example.iam.gserviceaccount.com",
    email_verified: true,
    sub: "pubsub-subject",
    exp: Math.floor(Date.now() / 1000) + 300,
  });
  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        packageName: "jp.miyamibu.urlalbum",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }),
      mismatchedEmail,
    ),
    "google_oidc_service_account_email_invalid",
  );

  const unverifiedEmail = googleOptions();
  unverifiedEmail.verifyPushToken = async () => ({
    iss: "https://accounts.google.com",
    aud: unverifiedEmail.audience,
    email: "pubsub-push@example.iam.gserviceaccount.com",
    email_verified: false,
    sub: "pubsub-subject",
    exp: Math.floor(Date.now() / 1000) + 300,
  });
  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        packageName: "jp.miyamibu.urlalbum",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }),
      unverifiedEmail,
    ),
    "google_oidc_email_unverified",
  );
});

Deno.test("Google subscription adapter fails closed without Play credentials, API, or product binding", async () => {
  const base = googleOptions();
  await assertRejects(
    () => lookupGooglePlaySubscription(
      "jp.mimac.urlsaver",
      "urlsaver.standard.monthly",
      "fixture-purchase-token",
      { ...base, serviceAccount: null },
    ),
    "invalid_google_play_package_name",
  );

  await assertRejects(
    () => lookupGooglePlaySubscription(
      base.packageName,
      "urlsaver.standard.monthly",
      "fixture-purchase-token",
      { ...base, serviceAccount: null },
    ),
    "google_play_service_account_credentials_missing",
  );

  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        packageName: "jp.miyamibu.urlalbum",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }),
      googleOptions(googleSubscriptionResponse(), 503),
    ),
    "google_play_subscription_lookup_failed",
  );

  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        packageName: "jp.miyamibu.urlalbum",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }),
      googleOptions(googleSubscriptionResponse({
        lineItems: [{
          productId: "attacker.product",
          expiryTime: "2026-08-27T00:00:00.000Z",
        }],
      })),
    ),
    "google_product_mismatch",
  );
});

Deno.test("Google RTDN revoke and full refund are the only notification-only grant removals", async () => {
  assertEquals(mapGoogleSubscriptionNotification(12).action, "revoke", "Google revoke action");
  assertEquals(mapGoogleSubscriptionNotification(13).action, "revoke", "Google expiry action");
  assertEquals(mapGoogleSubscriptionNotification(5).action, "revoke", "Google on-hold action");
  assertEquals(mapGoogleSubscriptionNotification(5).state, "on_hold", "Google on-hold state");
  assertEquals(mapGoogleSubscriptionNotification(6).state, "grace_period", "Google grace state");
  assertEquals(mapGoogleSubscriptionNotification(10).action, "revoke", "Google paused action");
  assertEquals(mapGoogleSubscriptionNotification(10).state, "paused", "Google paused state");

  const command = await normalizeGooglePubSubEnvelope(
    googleEnvelope({
      version: "1.0",
      packageName: "jp.miyamibu.urlalbum",
      eventTimeMillis: "1782432000000",
      voidedPurchaseNotification: {
        purchaseToken: "fixture-purchase-token",
        orderId: "GPA.0000-0000-0000-00000",
        productType: 1,
        refundType: 1,
      },
    }, "google-message-voided"),
    googleOptions(),
  );
  assertEquals(command.action, "revoke", "full void action");
  assertEquals(command.subscriptionState, "refunded", "full void state");
});

Deno.test("Google provider test notification is non-grant and contains no token", async () => {
  const command = await normalizeGooglePubSubEnvelope(
    googleEnvelope({
      version: "1.0",
      packageName: "jp.miyamibu.urlalbum",
      eventTimeMillis: "1782432000000",
      testNotification: { version: "1.0" },
    }, "google-message-test"),
    googleOptions(),
  );
  assertEquals(command.eventType, "test", "Google test event type");
  assertEquals(command.action, "noop", "Google test action");
  assertEquals(command.purchaseTokenHash, null, "Google test token absence");
});

Deno.test("Google RTDN rejects issuer and package mismatches before DB RPC", async () => {
  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        version: "1.0",
        packageName: "attacker.package",
        eventTimeMillis: "1782432000000",
        subscriptionNotification: {
          notificationType: 2,
          purchaseToken: "fixture-purchase-token",
          subscriptionId: "urlsaver.standard.monthly",
        },
      }),
      {
        ...googleOptions(),
        verifyPushToken: async () => ({
          iss: "https://attacker.example",
          aud: "https://staging.example.test/store-notification",
          sub: "pubsub-subject",
          exp: Math.floor(Date.now() / 1000) + 300,
        }),
      },
    ),
    "google_oidc_issuer_invalid",
  );
});

Deno.test("Google receiver rejects a non-canonical configured package", async () => {
  await assertRejects(
    () => normalizeGooglePubSubEnvelope(
      googleEnvelope({
        packageName: "jp.mimac.urlsaver",
        eventTimeMillis: "1782432000000",
        testNotification: { version: "1.0" },
      }),
      { ...googleOptions(), packageName: "jp.mimac.urlsaver" },
    ),
    "invalid_google_play_package_name",
  );
});
