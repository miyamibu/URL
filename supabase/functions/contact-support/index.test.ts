import {
  acceptedResponse,
  hmacSha256Hex,
  normalizeContactRequest,
  readContactRequest,
  validateContactRequest,
} from "./index.ts";
import {
  decryptContactSupportPayload,
  encryptContactSupportPayload,
} from "../_shared/contact-support-outbox-crypto.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(
      `${message}: expected ${String(expected)}, got ${String(actual)}`,
    );
  }
}

Deno.test("rate-limit identifiers use HMAC-SHA256", async () => {
  const digest = await hmacSha256Hex(
    "key",
    "The quick brown fox jumps over the lazy dog",
  );
  assertEquals(
    digest,
    "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
    "HMAC digest",
  );
});

Deno.test("contact request ignores client authentication claims", () => {
  const normalized = normalizeContactRequest({
    email: "user@example.com",
    name: "User",
    message: "hello",
    platform: "android",
    appVersion: "1.0",
    buildType: "release",
    idempotencyKey: "client-key-123456",
    isSignedIn: true,
    authUserId: "attacker-controlled-id",
  } as never);

  assert(
    !("isSignedIn" in normalized),
    "client sign-in claim must not enter normalized data",
  );
  assert(
    !("authUserId" in normalized),
    "client user id claim must not enter normalized data",
  );
});

Deno.test("idempotency header takes precedence and validation keeps bounds", () => {
  const normalized = normalizeContactRequest(
    {
      email: "user@example.com",
      name: "User",
      message: "hello",
      platform: "ios",
      appVersion: "1.0",
      buildType: "debug",
      idempotencyKey: "body-key-123456",
    },
    "header-key-123456",
  );

  assertEquals(
    normalized.idempotencyKey,
    "header-key-123456",
    "header key precedence",
  );
  assertEquals(validateContactRequest(normalized), null, "valid request");
  assertEquals(
    validateContactRequest({ ...normalized, idempotencyKey: "short" }),
    "invalid_idempotency_key",
    "short key rejection",
  );
  assertEquals(
    validateContactRequest({ ...normalized, message: "x".repeat(8193) }),
    "message_too_long",
    "message size rejection",
  );
});

Deno.test("contact acceptance response is 202 with accepted status and UUID", async () => {
  const requestId = "11111111-1111-4111-8111-111111111111";
  const response = acceptedResponse(requestId);
  assertEquals(response.status, 202, "acceptance status");
  const body = await response.json();
  assertEquals(body.status, "accepted", "acceptance body status");
  assertEquals(body.requestId, requestId, "acceptance request id");
});

Deno.test("contact request body size is capped before JSON processing", async () => {
  const oversized = new Request("https://contact-support.test", {
    method: "POST",
    body: "x".repeat(16 * 1024 + 1),
  });
  let rejected = false;
  try {
    await readContactRequest(oversized);
  } catch {
    rejected = true;
  }
  assert(rejected, "oversized request must be rejected");
});

Deno.test("outbox payload is encrypted and decryptable with a stable hash", async () => {
  const payload = {
    email: "user@example.com",
    name: "User",
    message: "private message",
    source: "mobile:android",
    platform: "android",
    appVersion: "1.0",
    buildType: "release",
    isSignedIn: false,
  };
  const key = "a".repeat(64);
  const first = await encryptContactSupportPayload(payload, key);
  const second = await encryptContactSupportPayload(payload, key);
  assert(
    first.envelope.ciphertext !== second.envelope.ciphertext,
    "encryption must use a fresh nonce",
  );
  assertEquals(
    first.payloadHash,
    second.payloadHash,
    "payload hash must be stable",
  );
  const decrypted = await decryptContactSupportPayload(first.envelope, key);
  assertEquals(
    JSON.stringify(decrypted),
    JSON.stringify(payload),
    "decrypted payload",
  );
});
