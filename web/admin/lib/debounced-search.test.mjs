import assert from "node:assert/strict";
import test from "node:test";

import { createDebouncedSearchController } from "./debounced-search.ts";

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

test("only the last scheduled search starts after the debounce window", async () => {
  const started = [];
  const results = [];
  const controller = createDebouncedSearchController(15, (result) => results.push(result), () => {
    throw new Error("unexpected search error");
  });

  controller.schedule(async () => {
    started.push("first");
    return "first";
  });
  controller.schedule(async () => {
    started.push("second");
    return "second";
  });
  await wait(40);

  assert.deepEqual(started, ["second"]);
  assert.deepEqual(results, ["second"]);
  controller.cancel();
});

test("in-flight requests are aborted and stale results cannot update state", async () => {
  const results = [];
  let firstAborted = false;
  const controller = createDebouncedSearchController(0, (result) => results.push(result), () => {
    throw new Error("unexpected search error");
  });

  controller.schedule((signal) => new Promise((resolve) => {
    signal.addEventListener("abort", () => {
      firstAborted = true;
      setTimeout(() => resolve("stale-first"), 5);
    }, { once: true });
  }));
  await wait(10);
  controller.schedule(async () => "fresh-second");
  await wait(30);

  assert.equal(firstAborted, true);
  assert.deepEqual(results, ["fresh-second"]);
  controller.cancel();
});
