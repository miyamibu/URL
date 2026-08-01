import assert from "node:assert/strict";
import test from "node:test";

import {
  MAX_ADMIN_JSON_BODY_BYTES,
  parseJsonObject,
  RequestBodyError,
} from "./request-body.ts";

test("JSON null and arrays fail closed as non-object request bodies", async () => {
  for (const value of ["null", "[]", "\"text\""]) {
    await assert.rejects(
      () => parseJsonObject(new Request("http://localhost", { method: "POST", body: value })),
      (error) => error instanceof RequestBodyError
        && error.status === 400
        && error.code === "json_body_object_required",
    );
  }
});

test("malformed JSON is rejected before route field validation", async () => {
  await assert.rejects(
    () => parseJsonObject(new Request("http://localhost", { method: "POST", body: "{" })),
    (error) => error instanceof RequestBodyError
      && error.status === 400
      && error.code === "invalid_json_body",
  );
});

test("declared and streamed bodies cannot exceed the request limit", async () => {
  const declaredTooLarge = new Request("http://localhost", {
    method: "POST",
    headers: { "content-length": String(MAX_ADMIN_JSON_BODY_BYTES + 1) },
    body: "{}",
  });
  await assert.rejects(
    () => parseJsonObject(declaredTooLarge),
    (error) => error instanceof RequestBodyError
      && error.status === 413
      && error.code === "request_body_too_large",
  );

  const oversized = new Request("http://localhost", {
    method: "POST",
    body: JSON.stringify({ note: "x".repeat(MAX_ADMIN_JSON_BODY_BYTES) }),
  });
  await assert.rejects(
    () => parseJsonObject(oversized),
    (error) => error instanceof RequestBodyError
      && error.status === 413
      && error.code === "request_body_too_large",
  );
});

test("valid JSON objects are returned without changing their fields", async () => {
  const body = await parseJsonObject(new Request("http://localhost", {
    method: "POST",
    body: JSON.stringify({ operationId: "op-1", reason: "review" }),
  }));
  assert.deepEqual(body, { operationId: "op-1", reason: "review" });
});
