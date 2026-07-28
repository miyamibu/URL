import {
  authorizeWorker,
  extractResendMessageId,
  handleRequest,
  parseOutboxPayload,
  parseWorkerRequest,
} from "./index.ts";
import { encryptContactSupportPayload } from "../_shared/contact-support-outbox-crypto.ts";

const SERVICE_ROLE_KEY = "s".repeat(40);
const WORKER_SECRET = "w".repeat(40);
const BASE_ENV: Record<string, string> = {
  SUPABASE_URL: "https://rinbam.example.supabase.co",
  SUPABASE_SERVICE_ROLE_KEY: SERVICE_ROLE_KEY,
  CONTACT_SUPPORT_WORKER_SECRET: WORKER_SECRET,
  CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY: "a".repeat(64),
  RESEND_API_KEY: "re_test_key",
  CONTACT_TO_EMAIL: "support@example.com",
  CONTACT_FROM_EMAIL: "noreply@example.com",
};

Deno.test("worker request parser keeps the batch limit bounded", () => {
  if (parseWorkerRequest("").limit !== 10) throw new Error("default limit");
  if (parseWorkerRequest('{"limit":50}').limit !== 50) {
    throw new Error("maximum limit");
  }
  for (
    const body of [
      '{"limit":0}',
      '{"limit":51}',
      '{"limit":"10"}',
      '{"unexpected":true}',
    ]
  ) {
    let rejected = false;
    try {
      parseWorkerRequest(body);
    } catch {
      rejected = true;
    }
    if (!rejected) throw new Error(`accepted invalid body: ${body}`);
  }
});

Deno.test("outbox payload parser rejects oversized or incompatible payloads", () => {
  const valid = {
    email: "user@example.com",
    name: "User",
    message: "Please help",
    source: "mobile:android",
    platform: "android",
    appVersion: "1.0.15",
    buildType: "release",
    isSignedIn: false,
  };
  if (!parseOutboxPayload(valid)) throw new Error("valid payload rejected");
  if (parseOutboxPayload({ ...valid, isSignedIn: "false" })) {
    throw new Error("non-boolean signed-in flag accepted");
  }
  if (parseOutboxPayload({ ...valid, message: "x".repeat(8193) })) {
    throw new Error("oversized message accepted");
  }
  if (parseOutboxPayload({ ...valid, platform: "web" })) {
    throw new Error("unsupported platform accepted");
  }
});

async function encryptedValidPayload(): Promise<Record<string, unknown>> {
  const payload = {
    email: "user@example.com",
    name: "User",
    message: "Please help",
    source: "mobile:android",
    platform: "android" as const,
    appVersion: "1.0.15",
    buildType: "release" as const,
    isSignedIn: false,
  };
  return (await encryptContactSupportPayload(
    payload,
    BASE_ENV.CONTACT_SUPPORT_OUTBOX_ENCRYPTION_KEY,
  )).envelope;
}

Deno.test("worker authorization requires the dedicated worker secret", () => {
  const request = new Request("https://worker.example", {
    method: "POST",
    headers: { "x-contact-support-worker-secret": WORKER_SECRET },
  });
  if (!authorizeWorker(request, { workerSecret: WORKER_SECRET })) {
    throw new Error("valid worker credentials rejected");
  }

  const missingWorkerSecret = new Request("https://worker.example", {
    method: "POST",
    headers: { authorization: `Bearer ${SERVICE_ROLE_KEY}` },
  });
  if (authorizeWorker(missingWorkerSecret, { workerSecret: WORKER_SECRET })) {
    throw new Error("missing worker secret accepted");
  }

  const mismatchedServiceRole = new Request("https://worker.example", {
    method: "POST",
    headers: {
      authorization: "Bearer stale-service-role-key",
      "x-contact-support-worker-secret": WORKER_SECRET,
    },
  });
  if (
    !authorizeWorker(mismatchedServiceRole, { workerSecret: WORKER_SECRET })
  ) throw new Error("worker secret was coupled to service-role rotation");
});

Deno.test("worker route rejects a body above the hard limit", async () => {
  const originalValues = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(BASE_ENV)) {
    originalValues.set(key, Deno.env.get(key));
    Deno.env.set(key, value);
  }
  const originalFetch = globalThis.fetch;
  globalThis.fetch = () =>
    Promise.reject(new Error("claim must not run for an oversized body"));

  try {
    const response = await handleRequest(
      new Request("https://worker.example", {
        method: "POST",
        headers: {
          authorization: `Bearer ${SERVICE_ROLE_KEY}`,
          "x-contact-support-worker-secret": WORKER_SECRET,
        },
        body: "x".repeat(8193),
      }),
    );
    if (response.status !== 413) throw new Error(`status ${response.status}`);
  } finally {
    globalThis.fetch = originalFetch;
    for (const [key, value] of originalValues) {
      if (value === undefined) Deno.env.delete(key);
      else Deno.env.set(key, value);
    }
  }
});

Deno.test("Resend success without a message id is not a success", async () => {
  const emptyId = await extractResendMessageId(
    new Response(JSON.stringify({}), { status: 200 }),
  );
  if (emptyId !== null) throw new Error("empty message id accepted");

  const messageId = await extractResendMessageId(
    new Response(JSON.stringify({ id: "  msg_test_1  " }), { status: 200 }),
  );
  if (messageId !== "msg_test_1") throw new Error("message id not normalized");
});

Deno.test("worker fails the claimed row when Resend omits its message id", async () => {
  const originalFetch = globalThis.fetch;
  const originalValues = new Map<string, string | undefined>();
  for (const [key, value] of Object.entries(BASE_ENV)) {
    originalValues.set(key, Deno.env.get(key));
    Deno.env.set(key, value);
  }

  const calls: string[] = [];
  const encryptedPayload = await encryptedValidPayload();
  globalThis.fetch = (input, _init) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/claim_contact_support_outbox_batch")) {
      return Promise.resolve(
        new Response(
          JSON.stringify([{
            outbox_id: "11111111-1111-4111-8111-111111111111",
            request_id: "22222222-2222-4222-8222-222222222222",
            lease_token: "33333333-3333-4333-8333-333333333333",
            attempts: 1,
            payload: encryptedPayload,
          }]),
          { status: 200 },
        ),
      );
    }
    if (url === "https://api.resend.com/emails") {
      return Promise.resolve(new Response(JSON.stringify({}), { status: 200 }));
    }
    if (url.endsWith("/fail_contact_support_outbox")) {
      return Promise.resolve(new Response("null", { status: 200 }));
    }
    if (url.endsWith("/record_contact_support_worker_heartbeat")) {
      return Promise.resolve(new Response("null", { status: 200 }));
    }
    if (url.endsWith("/complete_contact_support_outbox")) {
      throw new Error("complete must not be called");
    }
    throw new Error(`unexpected fetch: ${url}`);
  };

  try {
    const response = await handleRequest(
      new Request("https://worker.example", {
        method: "POST",
        headers: {
          authorization: `Bearer ${SERVICE_ROLE_KEY}`,
          "x-contact-support-worker-secret": WORKER_SECRET,
        },
      }),
    );
    if (response.status !== 200) throw new Error(`status ${response.status}`);
    const body = await response.json() as { claimed?: number; failed?: number };
    if (body.claimed !== 1 || body.failed !== 1) {
      throw new Error(`unexpected worker result: ${JSON.stringify(body)}`);
    }
    if (!calls.some((url) => url.endsWith("/fail_contact_support_outbox"))) {
      throw new Error("missing fail RPC");
    }
    if (calls.some((url) => url.endsWith("/complete_contact_support_outbox"))) {
      throw new Error("complete RPC was called without a message id");
    }
  } finally {
    globalThis.fetch = originalFetch;
    for (const [key, value] of originalValues) {
      if (value === undefined) Deno.env.delete(key);
      else Deno.env.set(key, value);
    }
  }
});
