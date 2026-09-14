import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(
  new URL("../web/invite-link/auth/reset-password/reset-password.js", import.meta.url),
  "utf8",
);

class FakeElement {
  constructor({ hidden = false } = {}) {
    this.className = "";
    this.disabled = false;
    this.focused = false;
    this.hidden = hidden;
    this.listeners = new Map();
    this.textContent = "";
    this.value = "";
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  focus() {
    this.focused = true;
  }
}

async function settle() {
  for (let index = 0; index < 4; index += 1) {
    await new Promise((resolve) => setImmediate(resolve));
  }
}

function deferred() {
  let resolve;
  const promise = new Promise((resolver) => {
    resolve = resolver;
  });
  return { promise, resolve };
}

async function runPage({
  search = "",
  hash = "",
  exchangeError = null,
  setSessionError = null,
  emitPasswordRecovery = false,
  sdkLoadPending = false,
  exchangePending = false,
  setSessionPending = false,
  triggerTimeout = false,
  resolvePendingAfterTimeout = false,
  updateUserThrows = false,
} = {}) {
  const elements = {
    status: new FakeElement(),
    form: new FakeElement({ hidden: true }),
    password: new FakeElement(),
    "confirm-password": new FakeElement(),
    submit: new FakeElement(),
  };
  const metrics = {
    createClient: 0,
    exchangeCodeForSession: 0,
    setSession: 0,
    onAuthStateChange: 0,
    unsubscribe: 0,
    replaceState: [],
    timeoutMs: [],
    updateUser: 0,
    events: [],
  };
  const pending = {
    exchange: deferred(),
    sdk: deferred(),
    setSession: deferred(),
  };
  const timers = new Map();
  let nextTimerId = 1;
  let sdkScript;
  let authStateListener;
  let clientOptions;

  const sessionResult = { data: { session: { user: { id: "test-user" } } }, error: null };
  const auth = {
    onAuthStateChange(listener) {
      metrics.onAuthStateChange += 1;
      authStateListener = listener;
      return {
        data: {
          subscription: {
            unsubscribe() {
              metrics.unsubscribe += 1;
            },
          },
        },
      };
    },
    async exchangeCodeForSession() {
      metrics.exchangeCodeForSession += 1;
      metrics.events.push("exchangeCodeForSession");
      if (emitPasswordRecovery) authStateListener?.("PASSWORD_RECOVERY");
      if (exchangePending) return pending.exchange.promise;
      if (exchangeError) return { data: { session: null }, error: exchangeError };
      return sessionResult;
    },
    async setSession() {
      metrics.setSession += 1;
      metrics.events.push("setSession");
      if (emitPasswordRecovery) authStateListener?.("PASSWORD_RECOVERY");
      if (setSessionPending) return pending.setSession.promise;
      if (setSessionError) return { data: { session: null }, error: setSessionError };
      return sessionResult;
    },
    async updateUser() {
      metrics.updateUser += 1;
      if (updateUserThrows) throw new Error("network failure");
      return { error: null };
    },
    async signOut() {
      return { error: null };
    },
  };

  const location = {
    search,
    hash,
    pathname: "/auth/reset-password",
  };
  const window = {
    location,
    history: {
      replaceState(_state, _title, url) {
        metrics.replaceState.push(url);
        metrics.events.push("replaceState");
        location.search = "";
        location.hash = "";
      },
    },
    setTimeout(callback, delay) {
      const timerId = nextTimerId;
      nextTimerId += 1;
      metrics.timeoutMs.push(delay);
      metrics.events.push("timeoutScheduled");
      timers.set(timerId, callback);
      return timerId;
    },
    clearTimeout(timerId) {
      timers.delete(timerId);
    },
  };
  const context = {
    URLSearchParams,
    document: {
      title: "りんばむ パスワード再設定",
      getElementById(id) {
        return elements[id];
      },
      createElement() {
        if (!sdkLoadPending) throw new Error("SDK loader must not run when a test client is present");
        sdkScript = new FakeElement();
        return sdkScript;
      },
      head: {
        appendChild(script) {
          if (!sdkLoadPending || script !== sdkScript) {
            throw new Error("Unexpected SDK loader append");
          }
          metrics.events.push("sdkAppended");
        },
      },
    },
    supabase: sdkLoadPending ? undefined : {
      createClient(_url, _key, options) {
        metrics.createClient += 1;
        metrics.events.push("createClient");
        clientOptions = options;
        return { auth };
      },
    },
    window,
  };
  context.globalThis = context;

  vm.runInNewContext(source, context, { filename: "reset-password.js" });
  await settle();
  if (triggerTimeout) {
    for (const callback of [...timers.values()]) callback();
    await settle();
  }
  if (resolvePendingAfterTimeout) {
    if (sdkLoadPending) {
      sdkScript?.listeners.get("load")?.();
      pending.sdk.resolve();
    }
    if (exchangePending) pending.exchange.resolve(sessionResult);
    if (setSessionPending) pending.setSession.resolve(sessionResult);
    await settle();
  }
  return { clientOptions, elements, metrics };
}

test("ordinary implicit access token fragments are scrubbed and rejected before SDK initialization", async () => {
  const result = await runPage({ hash: "#access_token=ordinary-token&token_type=bearer" });

  assert.equal(result.metrics.createClient, 0);
  assert.equal(result.elements.form.hidden, true);
  assert.match(result.elements.status.textContent, /有効期限|正しくありません/);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
});

test("tokenless reset visits remain explicit errors without rewriting the clean URL", async () => {
  const result = await runPage();

  assert.equal(result.metrics.createClient, 0);
  assert.equal(result.elements.form.hidden, true);
  assert.deepEqual(result.metrics.replaceState, []);
});

test("implicit recovery requires type and both tokens, uses a memory-only session, and initializes once", async () => {
  const result = await runPage({
    hash: "#type=recovery&access_token=recovery-access&refresh_token=recovery-refresh",
    emitPasswordRecovery: true,
  });

  assert.equal(result.clientOptions.auth.detectSessionInUrl, false);
  assert.equal(result.clientOptions.auth.persistSession, false);
  assert.equal(result.clientOptions.auth.autoRefreshToken, false);
  assert.equal(result.metrics.createClient, 1);
  assert.equal(result.metrics.setSession, 1);
  assert.equal(result.metrics.exchangeCodeForSession, 0);
  assert.equal(result.metrics.onAuthStateChange, 1);
  assert.equal(result.metrics.unsubscribe, 1);
  assert.equal(result.elements.form.hidden, false);
  assert.equal(result.elements.password.focused, true);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
  assert.ok(result.metrics.events.indexOf("replaceState") < result.metrics.events.indexOf("createClient"));
});

test("PKCE accepts code only and removes credentials before SDK initialization", async () => {
  const result = await runPage({
    search: "?code=pkce-code",
    hash: "#access_token=ignored-ordinary-token",
    emitPasswordRecovery: true,
  });

  assert.equal(result.metrics.createClient, 1);
  assert.equal(result.metrics.exchangeCodeForSession, 1);
  assert.equal(result.metrics.setSession, 0);
  assert.equal(result.elements.form.hidden, false);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
  assert.ok(result.metrics.events.indexOf("replaceState") < result.metrics.events.indexOf("exchangeCodeForSession"));
});

test("invalid or expired structurally valid recovery is scrubbed and stays in the explicit error state", async () => {
  const result = await runPage({
    search: "?code=expired-code",
    exchangeError: new Error("expired"),
  });

  assert.equal(result.metrics.exchangeCodeForSession, 1);
  assert.equal(result.elements.form.hidden, true);
  assert.match(result.elements.status.textContent, /有効期限|正しくありません/);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
  assert.equal(result.metrics.unsubscribe, 1);
});

test("a non-recovery implicit fragment is rejected even when both tokens exist", async () => {
  const result = await runPage({
    hash: "#type=magiclink&access_token=ordinary-access&refresh_token=ordinary-refresh",
  });

  assert.equal(result.metrics.createClient, 0);
  assert.equal(result.elements.form.hidden, true);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
});

test("SDK load timeout fails closed after scrubbing and ignores a late load", async () => {
  const result = await runPage({
    search: "?code=pkce-code",
    sdkLoadPending: true,
    triggerTimeout: true,
    resolvePendingAfterTimeout: true,
  });

  assert.deepEqual(result.metrics.timeoutMs, [15_000]);
  assert.deepEqual(result.metrics.replaceState, ["/auth/reset-password"]);
  assert.equal(result.metrics.createClient, 0);
  assert.equal(result.elements.form.hidden, true);
  assert.match(result.elements.status.textContent, /タイムアウト|再発行/);
});

for (const pendingOperation of ["exchange", "setSession"]) {
  test(`${pendingOperation} timeout fails closed and a late session cannot reveal the form`, async () => {
    const result = await runPage({
      search: pendingOperation === "exchange" ? "?code=pkce-code" : "",
      hash: pendingOperation === "setSession"
        ? "#type=recovery&access_token=recovery-access&refresh_token=recovery-refresh"
        : "",
      exchangePending: pendingOperation === "exchange",
      setSessionPending: pendingOperation === "setSession",
      triggerTimeout: true,
      resolvePendingAfterTimeout: true,
    });

    assert.deepEqual(result.metrics.timeoutMs, [15_000]);
    assert.equal(result.elements.form.hidden, true);
    assert.equal(result.elements.password.focused, false);
    assert.match(result.elements.status.textContent, /タイムアウト|再発行/);
    assert.equal(result.metrics.unsubscribe, 1);
  });
}

test("an updateUser exception always re-enables submit and returns to reissue guidance", async () => {
  const result = await runPage({
    search: "?code=pkce-code",
    updateUserThrows: true,
  });
  result.elements.password.value = "password-123";
  result.elements["confirm-password"].value = "password-123";

  await result.elements.form.listeners.get("submit")({ preventDefault() {} });

  assert.equal(result.metrics.updateUser, 1);
  assert.equal(result.elements.submit.disabled, false);
  assert.equal(result.elements.form.hidden, false);
  assert.match(result.elements.status.textContent, /再発行|やり直し/);
  assert.match(result.elements.status.className, /error/);
});
