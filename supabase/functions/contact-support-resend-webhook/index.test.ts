import {
  deliveryErrorFor,
  deliveryRank,
  readBodyText,
  statusForEvent,
} from "./index.ts";

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

Deno.test("webhook event mapping covers supported Resend statuses", () => {
  assertEquals(statusForEvent("email.sent"), "sent", "sent mapping");
  assertEquals(
    statusForEvent("email.delivered"),
    "delivered",
    "delivered mapping",
  );
  assertEquals(statusForEvent("email.bounced"), "bounced", "bounced mapping");
  assertEquals(
    statusForEvent("email.unknown"),
    undefined,
    "unknown event ignored",
  );
});

Deno.test("delivery status rank does not allow a later lower state", () => {
  assert(
    deliveryRank("delivered") > deliveryRank("sent"),
    "delivered must outrank sent",
  );
  assert(
    deliveryRank("failed") > deliveryRank("delivered"),
    "failed must outrank delivered",
  );
  assert(
    deliveryRank("sent") < deliveryRank("delivered"),
    "sent must not regress delivered",
  );
});

Deno.test("webhook body size is capped", async () => {
  const oversized = new Request("https://contact-support-webhook.test", {
    method: "POST",
    body: "x".repeat(64 * 1024 + 1),
  });
  let rejected = false;
  try {
    await readBodyText(oversized);
  } catch {
    rejected = true;
  }
  assert(rejected, "oversized webhook must be rejected");
});

Deno.test("provider error text is bounded", () => {
  const error = deliveryErrorFor({
    type: "email.failed",
    data: { error: "x".repeat(1024) },
  });
  assert(
    error !== null && error.length <= 512,
    "provider error must be bounded",
  );
});
