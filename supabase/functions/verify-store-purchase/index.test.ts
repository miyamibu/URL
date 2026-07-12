import { APPLE_ROOT_CERTIFICATES } from "./apple-root-certificates.ts";
import {
  constantTimeStringEqual,
  expectedPlanForProduct,
  handleRequest,
  normalizeRequest,
} from "./index.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) throw new Error(`${message}: expected ${String(expected)}, got ${String(actual)}`);
}

Deno.test("Apple root certificate manifest matches its recorded SHA-256 values", async () => {
  for (const certificate of APPLE_ROOT_CERTIFICATES) {
    const der = Uint8Array.from(
      atob(certificate.derBase64.replace(/\s+/g, "")),
      (character) => character.charCodeAt(0),
    );
    const digest = await crypto.subtle.digest("SHA-256", der);
    const actual = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
    assertEquals(actual, certificate.sha256, `${certificate.name} hash`);
  }
});

Deno.test("purchase policy helpers reject unknown products and bind strings", async () => {
  assertEquals(expectedPlanForProduct("urlsaver.standard.monthly")?.plan, "standard", "standard product");
  assertEquals(expectedPlanForProduct("urlsaver.pro.yearly")?.billingPeriod, "yearly", "pro product");
  assertEquals(expectedPlanForProduct("urlsaver.free.monthly"), null, "unknown product");
  assert(await constantTimeStringEqual("user-a", "user-a"), "equal binding");
  assert(!(await constantTimeStringEqual("user-a", "user-b")), "mismatched binding");
  assert(!(await constantTimeStringEqual("", "user-a")), "missing binding");
});

Deno.test("malformed App Store JWS fails closed without granting", async () => {
  const originalFetch = globalThis.fetch;
  const originalValues = new Map<string, string | undefined>();
  const env = {
    SUPABASE_URL: "https://purchase-security-test.invalid",
    SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
    APP_STORE_BUNDLE_ID: "com.mibu.codebridge.ios",
    APP_STORE_ENVIRONMENT: "Sandbox",
  };
  for (const [key, value] of Object.entries(env)) {
    originalValues.set(key, Deno.env.get(key));
    Deno.env.set(key, value);
  }

  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.endsWith("/auth/v1/user")) {
      return new Response(JSON.stringify({ id: "00000000-0000-0000-0000-000000000001" }), { status: 200 });
    }
    if (url.includes("/rest/v1/subscription_products?")) {
      return new Response(JSON.stringify([{ plan: "standard", billing_period: "monthly" }]), { status: 200 });
    }
    if (url.includes("/rest/v1/store_purchase_verifications?")) {
      return new Response("[]", { status: 200 });
    }
    if (url.endsWith("/rest/v1/store_purchase_verifications") && init?.method === "POST") {
      return new Response(JSON.stringify([{ id: "verification-test-id" }]), { status: 201 });
    }
    throw new Error(`unexpected test request: ${url}`);
  };

  try {
    const response = await handleRequest(
      new Request("https://purchase-security-test.invalid/functions/v1/verify-store-purchase", {
        method: "POST",
        headers: { authorization: "Bearer test", "content-type": "application/json" },
        body: JSON.stringify({
          storePlatform: "app_store",
          storeProductId: "urlsaver.standard.monthly",
          purchaseToken: "eyJhbGciOiJFUzI1NiIsIng1YyI6WyJhdHRhY2tlciJdfQ.invalid.signature",
        }),
      }),
    );
    assertEquals(response.status, 502, "malformed JWS status");
    const body = await response.json();
    assert(body.error !== "verified", "malformed JWS must not grant");
  } finally {
    globalThis.fetch = originalFetch;
    for (const [key, value] of originalValues) {
      if (value === undefined) Deno.env.delete(key);
      else Deno.env.set(key, value);
    }
  }
});

Deno.test("request normalization does not accept a missing purchase token", () => {
  let rejected = false;
  try {
    normalizeRequest({ storePlatform: "app_store", storeProductId: "urlsaver.standard.monthly" });
  } catch {
    rejected = true;
  }
  assert(rejected, "missing purchase token should be rejected");
});
