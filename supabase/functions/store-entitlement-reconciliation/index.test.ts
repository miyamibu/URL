import {
  createAppleServerApiJwt,
  handleRequest,
  lookupAppleSubscription,
  mapAppleSubscriptionStatus,
  normalizeBindings,
  normalizeEventIds,
  reconcileCurrentStoreState,
} from "./index.ts";
import { sha256Hex } from "../_shared/store-notification-contract.ts";
import {
  decodeJwt,
  decodeProtectedHeader,
  exportPKCS8,
  generateKeyPair,
} from "npm:jose@5.9.6";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
}

const EVENT_A = "00000000-0000-4000-8000-000000000001";
const EVENT_B = "00000000-0000-4000-8000-000000000002";

function withEnv(values: Record<string, string>, action: () => Promise<void>): Promise<void> {
  const previous = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(values)) {
    previous.set(key, Deno.env.get(key));
    Deno.env.set(key, value);
  }
  return action().finally(() => {
    for (const [key, value] of previous) {
      if (value === undefined) Deno.env.delete(key);
      else Deno.env.set(key, value);
    }
  });
}

Deno.test("reconciliation event ids are UUID-only, deduplicated, and bounded", () => {
  const values = normalizeEventIds([EVENT_A, EVENT_A.toUpperCase(), EVENT_B, "not-an-id"]);
  assertEquals(values.length, 2, "deduplicated event count");
  assertEquals(values[0], EVENT_A, "first event id");
  assertEquals(values[1], EVENT_B, "second event id");
});

Deno.test("reconciliation rejects missing or wrong secret before Supabase access", async () => {
  await withEnv({ STORE_RECONCILIATION_SECRET: "s".repeat(32) }, async () => {
    const originalFetch = globalThis.fetch;
    let called = false;
    globalThis.fetch = async () => {
      called = true;
      return new Response("unexpected", { status: 500 });
    };
    try {
      const response = await handleRequest(new Request("https://reconcile.test", {
        method: "POST",
        headers: { "x-store-reconciliation-secret": "wrong" },
        body: JSON.stringify({ eventIds: [EVENT_A] }),
      }));
      assertEquals(response.status, 401, "wrong secret status");
      assert(!called, "wrong secret must not call Supabase");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

Deno.test("reconciliation replays only event ids through the dedicated RPC", async () => {
  await withEnv({
    STORE_RECONCILIATION_SECRET: "s".repeat(32),
    SUPABASE_URL: "https://reconcile.supabase.test",
    SUPABASE_SERVICE_ROLE_KEY: "service-role-fixture",
  }, async () => {
    const originalFetch = globalThis.fetch;
    const calls: Array<{ url: string; body: string }> = [];
    globalThis.fetch = async (input, init) => {
      calls.push({ url: String(input), body: typeof init?.body === "string" ? init.body : "" });
      return new Response(JSON.stringify([{
        event_id: EVENT_A,
        result: "applied",
        grant_id: "00000000-0000-4000-8000-000000000099",
        user_id: "00000000-0000-4000-8000-000000000098",
        failure_reason: null,
        grant_changed: false,
      }]), { status: 200 });
    };
    try {
      const response = await handleRequest(new Request("https://reconcile.test", {
        method: "POST",
        headers: { "x-store-reconciliation-secret": "s".repeat(32) },
        body: JSON.stringify({ eventIds: [EVENT_A] }),
      }));
      assertEquals(response.status, 200, "reconciliation status");
      assertEquals(calls.length, 1, "one RPC call");
      assert(calls[0].url.endsWith("/rest/v1/rpc/reconcile_store_subscription_notification"), "dedicated RPC");
      assertEquals(JSON.parse(calls[0].body).p_event_row_id, EVENT_A, "event id only");
      const body = await response.json();
      assertEquals(body.results[0].result, "applied", "RPC result propagated");
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

Deno.test("Google active reconciliation hashes and binds the temporary token before lookup", async () => {
  const token = "fixture-active-purchase-token";
  const tokenHash = await sha256Hex(token);
  const calls: string[] = [];
  await withEnv({ GOOGLE_PLAY_PACKAGE_NAME: "jp.miyamibu.urlalbum" }, async () => {
    const result = await reconcileCurrentStoreState(
      "https://reconcile.supabase.test",
      "service-role-fixture",
      { eventId: EVENT_A, purchaseToken: token },
      {
        prepareTarget: async (_url, _key, eventId, binding) => {
          assertEquals(eventId, EVENT_A, "target event id");
          assert(binding !== undefined, "temporary token binding is passed to DB target lookup");
          assertEquals(binding.purchaseTokenHash, tokenHash, "stored token hash binding");
          return {
            event_id: EVENT_A,
            provider: "google_play",
            subscription_key: `google_play:${tokenHash}`,
            purchase_token_hash: tokenHash,
            store_product_id: "urlsaver.standard.monthly",
            store_transaction_id: null,
            original_transaction_id: null,
            user_id: "00000000-0000-4000-8000-000000000003",
            binding_status: "verified",
            failure_reason: null,
          };
        },
        lookupGoogle: async (packageName, productId, purchaseToken) => {
          calls.push(`${packageName}:${productId}`);
          assertEquals(purchaseToken, token, "raw token is used only for provider lookup");
          return {
            providerState: "SUBSCRIPTION_STATE_ACTIVE",
            productId,
            subscriptionState: "active",
            action: "activate",
            expiresAt: "2026-08-27T00:00:00.000Z",
            storeTransactionId: "GPA.fixture-order",
          };
        },
        applySnapshot: async (_url, _key, snapshot) => {
          assertEquals(snapshot.purchaseTokenHash, tokenHash, "only token hash reaches DB apply");
          assert(!JSON.stringify(snapshot).includes(token), "raw token must not reach DB apply");
          return {
            event_id: EVENT_A,
            result: "applied",
            grant_id: "00000000-0000-4000-8000-000000000004",
            user_id: "00000000-0000-4000-8000-000000000003",
            failure_reason: null,
            grant_changed: true,
          };
        },
      },
    );
    assertEquals(calls.length, 1, "one current Store lookup");
    assertEquals(result.mode, "google_current_state", "Google reconciliation mode");
    assertEquals(result.verification, "verified", "Google reconciliation verification");
    assertEquals(result.result, "applied", "authoritative state is applied");
  });
});

Deno.test("Google active reconciliation fails before the Store API on a hash mismatch", async () => {
  const token = "fixture-mismatched-purchase-token";
  let lookupCalled = false;
  await withEnv({ GOOGLE_PLAY_PACKAGE_NAME: "jp.miyamibu.urlalbum" }, async () => {
    try {
      await reconcileCurrentStoreState(
        "https://reconcile.supabase.test",
        "service-role-fixture",
        { eventId: EVENT_A, purchaseToken: token },
        {
          prepareTarget: async () => ({
            event_id: EVENT_A,
            provider: "google_play",
            subscription_key: "google_play:stored",
            purchase_token_hash: "a".repeat(64),
            store_product_id: "urlsaver.standard.monthly",
            store_transaction_id: null,
            original_transaction_id: null,
            user_id: null,
            binding_status: "verified",
            failure_reason: null,
          }),
          lookupGoogle: async () => {
            lookupCalled = true;
            throw new Error("lookup_must_not_run");
          },
        },
      );
    } catch (error) {
      assert(error instanceof Error, "expected mismatch error");
      assertEquals(error.message, "google_purchase_token_binding_mismatch", "binding mismatch code");
    }
    assert(!lookupCalled, "hash mismatch must stop before Store lookup");
  });
});

Deno.test("Apple active reconciliation verifies current state and applies the normalized snapshot", async () => {
  let applyCalled = false;
  let lookupTarget: { originalTransactionId: string; productId: string; userId: string } | null = null;
  const result = await reconcileCurrentStoreState(
    "https://reconcile.supabase.test",
    "service-role-fixture",
    {
      eventId: EVENT_B,
      signedTransactionInfo: "fixture-signed-transaction-info",
      transactionId: "apple-transaction-002",
    },
    {
      prepareTarget: async () => ({
        event_id: EVENT_B,
        provider: "app_store",
        subscription_key: "app_store:apple-original-002",
        purchase_token_hash: null,
        store_product_id: "urlsaver.standard.monthly",
        store_transaction_id: "apple-transaction-002",
        original_transaction_id: "apple-original-002",
        user_id: "00000000-0000-4000-8000-000000000005",
        binding_status: "verified",
        failure_reason: null,
      }),
      verifyAppleBinding: async (signedTransactionInfo, expected) => {
        assertEquals(signedTransactionInfo, "fixture-signed-transaction-info", "temporary JWS binding");
        assertEquals(expected.originalTransactionId, "apple-original-002", "Apple original transaction binding");
        return {
          transactionId: "apple-transaction-002",
          originalTransactionId: "apple-original-002",
          productId: "urlsaver.standard.monthly",
          appAccountToken: "00000000-0000-4000-8000-000000000005",
        };
      },
      lookupApple: async (_transactionId, target) => {
        lookupTarget = target;
        return {
          originalTransactionId: "apple-original-002",
          productId: "urlsaver.standard.monthly",
          userId: "00000000-0000-4000-8000-000000000005",
          transactionId: "apple-transaction-003",
          status: 1,
          action: "activate",
          subscriptionState: "active",
          expiresAt: "2026-08-27T00:00:00.000Z",
          observedAt: "2026-07-27T01:00:00.000Z",
          signedTransactionInfoHash: "a".repeat(64),
        };
      },
      applySnapshot: async (_url, _key, snapshot) => {
        applyCalled = true;
        assertEquals(snapshot.purchaseTokenHash, null, "Apple reconciliation has no purchase token");
        assertEquals(snapshot.storeTransactionId, "apple-transaction-003", "latest Apple transaction");
        assertEquals(snapshot.signedTransactionInfoHash, "a".repeat(64), "JWS hash only reaches DB");
        return {
          event_id: EVENT_B,
          result: "applied",
          grant_id: "00000000-0000-4000-8000-000000000006",
          user_id: "00000000-0000-4000-8000-000000000005",
          failure_reason: null,
          grant_changed: true,
        };
      },
    },
  );
  assertEquals(result.mode, "apple_current_state", "Apple reconciliation mode");
  assertEquals(result.verification, "verified", "Apple current state verification");
  assertEquals(result.result, "applied", "Apple authoritative state result");
  assertEquals(result.grant_changed, true, "Apple grant is updated");
  const resolvedLookupTarget = lookupTarget as unknown as {
    originalTransactionId: string;
    productId: string;
    userId: string;
  };
  assertEquals(resolvedLookupTarget.originalTransactionId, "apple-original-002", "Apple target original transaction");
  assertEquals(resolvedLookupTarget.productId, "urlsaver.standard.monthly", "Apple target product");
  assertEquals(resolvedLookupTarget.userId, "00000000-0000-4000-8000-000000000005", "Apple target user");
  assert(applyCalled, "verified Apple state must apply a grant snapshot");
});

Deno.test("Apple Server API lookup verifies response identity and selects the newest bound transaction", async () => {
  const target = {
    originalTransactionId: "apple-original-003",
    productId: "urlsaver.standard.monthly",
    userId: "00000000-0000-4000-8000-000000000007",
  };
  const nowMillis = Date.parse("2026-07-27T00:00:00.000Z");
  const calls: Array<{ url: string; authorization: string }> = [];
  await withEnv({
    APP_STORE_BUNDLE_ID: "com.mibu.codebridge.ios",
    APP_STORE_ENVIRONMENT: "Sandbox",
    APP_STORE_APPLE_ID: "6771251450",
    APP_STORE_SERVER_API_ISSUER_ID: "issuer-fixture",
    APP_STORE_SERVER_API_KEY_ID: "key-fixture",
    APP_STORE_SERVER_API_PRIVATE_KEY: "unused-fixture-key",
  }, async () => {
    const snapshot = await lookupAppleSubscription("apple-transaction-003", target, {
      nowMillis,
      jwtProvider: async (config, nowSeconds) => {
        assertEquals(config.bundleId, "com.mibu.codebridge.ios", "Apple JWT bundle id");
        assertEquals(nowSeconds, Math.floor(nowMillis / 1000), "Apple JWT clock");
        return "jwt-fixture";
      },
      fetch: async (input, init) => {
        calls.push({
          url: String(input),
          authorization: new Headers(init?.headers).get("authorization") ?? "",
        });
        return new Response(JSON.stringify({
          appAppleId: 6771251450,
          bundleId: "com.mibu.codebridge.ios",
          environment: "Sandbox",
          data: [{
            lastTransactions: [
              { status: 2, signedTransactionInfo: "older-jws" },
              { status: 1, signedTransactionInfo: "newer-jws" },
            ],
          }],
        }), { status: 200, headers: { "content-type": "application/json" } });
      },
      verifyTransaction: async (signedTransactionInfo, expected) => {
        assertEquals(expected.originalTransactionId, null, "API verifies JWS before binding filter");
        if (signedTransactionInfo === "older-jws") {
          return {
            transactionId: "apple-transaction-002",
            originalTransactionId: target.originalTransactionId,
            productId: target.productId,
            appAccountToken: target.userId,
            expiresAt: "2026-07-26T00:00:00.000Z",
            signedAt: "2026-07-26T00:00:00.000Z",
          };
        }
        return {
          transactionId: "apple-transaction-003",
          originalTransactionId: target.originalTransactionId,
          productId: target.productId,
          appAccountToken: target.userId,
          expiresAt: "2026-08-27T00:00:00.000Z",
          signedAt: "2026-07-27T00:00:00.000Z",
        };
      },
    });
    assertEquals(calls.length, 1, "one Apple Server API request");
    assert(calls[0].url.startsWith("https://api.storekit-sandbox.apple.com/inApps/v1/subscriptions/"), "sandbox endpoint");
    assertEquals(calls[0].authorization, "Bearer jwt-fixture", "JWT authorization header");
    assertEquals(snapshot.transactionId, "apple-transaction-003", "newest transaction selected");
    assertEquals(snapshot.status, 1, "Apple status selected");
    assertEquals(snapshot.action, "activate", "active Apple status maps to activate");
    assertEquals(snapshot.subscriptionState, "active", "active Apple status maps to active");
    assert(snapshot.signedTransactionInfoHash.length === 64, "only JWS hash is retained");
  });
});

Deno.test("Apple subscription status mapping revokes expired retry and preserves only valid grace time", () => {
  const nowMillis = Date.parse("2026-07-27T00:00:00.000Z");
  assertEquals(
    mapAppleSubscriptionStatus(3, "2026-07-28T00:00:00.000Z", null, nowMillis).action,
    "preserve",
    "billing retry remains active until its authoritative expiry",
  );
  assertEquals(
    mapAppleSubscriptionStatus(4, "2026-07-26T00:00:00.000Z", null, nowMillis).action,
    "revoke",
    "expired grace period revokes access",
  );
  assertEquals(
    mapAppleSubscriptionStatus(5, "2026-08-27T00:00:00.000Z", "2026-07-26T00:00:00.000Z", nowMillis).state,
    "revoked",
    "Apple revocation wins over expiry",
  );
});

Deno.test("Apple Server API JWT uses ES256 identity claims and a bounded expiration", async () => {
  const { privateKey } = await generateKeyPair("ES256", { extractable: true });
  const privateKeyPem = await exportPKCS8(privateKey);
  const issuedAt = 1785110400;
  const token = await createAppleServerApiJwt({
    issuerId: "issuer-fixture",
    keyId: "key-fixture",
    privateKey: privateKeyPem,
    bundleId: "com.mibu.codebridge.ios",
  }, issuedAt);
  const header = decodeProtectedHeader(token);
  const claims = decodeJwt(token);
  assertEquals(header.alg, "ES256", "Apple JWT algorithm");
  assertEquals(header.kid, "key-fixture", "Apple JWT key id");
  assertEquals(claims.iss, "issuer-fixture", "Apple JWT issuer");
  assertEquals(claims.aud, "appstoreconnect-v1", "Apple JWT audience");
  assertEquals(claims.bid, "com.mibu.codebridge.ios", "Apple JWT bundle claim");
  assertEquals(claims.iat, issuedAt, "Apple JWT issued-at");
  assertEquals(claims.exp, issuedAt + 300, "Apple JWT bounded expiration");
});

Deno.test("reconciliation bindings reject mixed provider payloads and raw token is not normalized into output", () => {
  let rejected = false;
  try {
    normalizeBindings([{
      eventId: EVENT_A,
      purchaseToken: "fixture-token",
      signedTransactionInfo: "fixture-jws",
    }], [EVENT_A]);
  } catch (error) {
    rejected = error instanceof Error && error.message === "reconciliation_binding_provider_ambiguous";
  }
  assert(rejected, "mixed Google and Apple bindings must be rejected");
});
