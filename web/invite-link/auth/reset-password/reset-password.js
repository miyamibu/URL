(() => {
  "use strict";

  const SUPABASE_URL = "https://xocumgxbylmpoobfqows.supabase.co";
  const SUPABASE_ANON_KEY = "sb_publishable_sqa8-DQgzLsiy34XygAjnQ_ebx28Wsb";
  const SUPABASE_SDK_URL = "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.53.0/dist/umd/supabase.min.js";
  const SUPABASE_SDK_INTEGRITY = "sha384-H9dj4GG/hgfwNjlYa740FF9geXbzyXSgepgoobvIAW49UUAcfk+GAiBnLDIs4hRh";
  const RECOVERY_TIMEOUT_MS = 15_000;
  const invalidLinkMessage = "再設定リンクの有効期限が切れているか、リンクが正しくありません。もう一度メールを送信してください。";
  const timeoutMessage = "再設定リンクの確認がタイムアウトしました。通信状況を確認し、再設定メールを再発行してやり直してください。";
  const status = document.getElementById("status");
  const form = document.getElementById("form");
  const password = document.getElementById("password");
  const confirmPassword = document.getElementById("confirm-password");
  const submit = document.getElementById("submit");
  const query = new URLSearchParams(window.location.search);
  const hash = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const pkceCode = query.get("code");
  const implicitRecoveryType = hash.get("type");
  const implicitAccessToken = hash.get("access_token");
  const implicitRefreshToken = hash.get("refresh_token");
  const hasPkceRecoveryInput = Boolean(pkceCode);
  const hasImplicitRecoveryInput = Boolean(
    implicitRecoveryType === "recovery" &&
    implicitAccessToken &&
    implicitRefreshToken,
  );
  const hasRecoveryInput = hasPkceRecoveryInput || hasImplicitRecoveryInput;
  const hasSensitiveUrlInput = ["code", "access_token", "refresh_token"].some(
    (key) => query.has(key) || hash.has(key),
  );
  let recoveryReady = false;
  let client;
  let recoveryInitialization;
  let authStateSubscription;

  function show(message, type = "") {
    status.textContent = message;
    status.className = `notice ${type}`.trim();
  }

  function failRecovery(message = invalidLinkMessage) {
    if (recoveryReady) return;
    form.hidden = true;
    show(message, "error");
  }

  function enableForm() {
    recoveryReady = true;
    form.hidden = false;
    show("リンク確認が完了しました。新しいパスワードを設定できます。", "success");
    password.focus();
  }

  function clearRecoveryCredentialsFromUrl() {
    window.history.replaceState(null, document.title, window.location.pathname);
  }

  function createRecoveryDeadline() {
    let active = true;
    let timeoutId;
    const timeout = new Promise((_, reject) => {
      timeoutId = window.setTimeout(() => {
        active = false;
        const error = new Error("Recovery initialization timed out");
        error.code = "RECOVERY_TIMEOUT";
        reject(error);
      }, RECOVERY_TIMEOUT_MS);
    });

    return {
      waitFor(operation) {
        return Promise.race([operation, timeout]);
      },
      isActive() {
        return active;
      },
      stop() {
        active = false;
        window.clearTimeout(timeoutId);
      },
    };
  }

  function stopAuthStateListener() {
    authStateSubscription?.unsubscribe();
    authStateSubscription = undefined;
  }

  function loadSupabaseSdk() {
    if (globalThis.supabase?.createClient) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = SUPABASE_SDK_URL;
      script.integrity = SUPABASE_SDK_INTEGRITY;
      script.crossOrigin = "anonymous";
      script.addEventListener("load", resolve, { once: true });
      script.addEventListener("error", reject, { once: true });
      document.head.appendChild(script);
    });
  }

  async function performRecoveryInitialization() {
    const deadline = createRecoveryDeadline();
    try {
      await deadline.waitFor(loadSupabaseSdk());
      if (!deadline.isActive()) return;

      client = globalThis.supabase.createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
        auth: {
          detectSessionInUrl: false,
          persistSession: false,
          autoRefreshToken: false,
        },
      });

      const authState = client.auth.onAuthStateChange((event) => {
        if (event === "PASSWORD_RECOVERY") {
          void initializeRecoverySession();
        }
      });
      authStateSubscription = authState.data.subscription;

      let result;
      if (hasPkceRecoveryInput) {
        result = await deadline.waitFor(client.auth.exchangeCodeForSession(pkceCode));
      } else {
        result = await deadline.waitFor(
          client.auth.setSession({
            access_token: implicitAccessToken,
            refresh_token: implicitRefreshToken,
          }),
        );
      }

      if (!deadline.isActive()) return;
      if (result.error || !result.data.session) throw result.error || new Error("Missing recovery session");
      enableForm();
    } catch (error) {
      failRecovery(error?.code === "RECOVERY_TIMEOUT" ? timeoutMessage : invalidLinkMessage);
    } finally {
      deadline.stop();
      stopAuthStateListener();
    }
  }

  function initializeRecoverySession() {
    if (!recoveryInitialization) {
      recoveryInitialization = performRecoveryInitialization();
    }
    return recoveryInitialization;
  }

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!recoveryReady || !client) {
      failRecovery();
      return;
    }
    if (password.value.length < 8) {
      show("パスワードは8文字以上で入力してください。", "error");
      return;
    }
    if (password.value !== confirmPassword.value) {
      show("確認用パスワードが一致していません。", "error");
      return;
    }

    submit.disabled = true;
    show("パスワードを変更しています…");
    let updateError;
    try {
      const result = await client.auth.updateUser({ password: password.value });
      updateError = result.error;
    } catch {
      updateError = new Error("Password update failed");
    } finally {
      submit.disabled = false;
    }
    if (updateError) {
      show("パスワードを変更できませんでした。リンクを再発行してやり直してください。", "error");
      return;
    }

    password.value = "";
    confirmPassword.value = "";
    form.hidden = true;
    try {
      await client.auth.signOut();
    } catch {
      // The recovery client is memory-only; a sign-out failure must not undo a successful password change.
    }
    show("パスワードを変更しました。管理画面に戻り、新しいパスワードでサインインしてください。", "success");
  });

  if (hasSensitiveUrlInput) {
    clearRecoveryCredentialsFromUrl();
  }

  if (!hasRecoveryInput) {
    failRecovery();
    return;
  }

  initializeRecoverySession();
})();
