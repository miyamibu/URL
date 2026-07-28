"use client";

import { createClient, Session, SupabaseClient } from "@supabase/supabase-js";
import Image from "next/image";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";

type PromoCodeRow = {
  id: string;
  target_email: string;
  created_at: string;
  expires_at: string | null;
  claimed_at: string | null;
  revoked_at: string | null;
  note: string | null;
  delivery_status: string;
  sent_at: string | null;
  delivery_message_id: string | null;
  delivery_event_type: string | null;
  delivery_event_at: string | null;
  delivery_error: string | null;
  revoked_reason: string | null;
  status_label: string;
};

type UserSearchRow = {
  id: string;
  email: string;
  createdAt?: string;
  lastSignInAt?: string;
};

type SendResult = {
  id: string;
  targetEmail: string;
  code: string;
  redeemLink: string;
  expiresAt: string;
};

type SupportRequestRow = {
  id: string;
  request_id: string;
  platform: string;
  app_version: string;
  build_type: string;
  is_signed_in: boolean;
  delivery_status: string;
  delivery_event_type: string | null;
  delivery_event_at: string | null;
  delivery_error: string | null;
  support_status: "open" | "in_progress" | "resolved" | "closed";
  assigned_admin_id: string | null;
  admin_note: string | null;
  created_at: string;
  updated_at: string;
};

type ModerationReportRow = {
  id: string;
  reported_user_id: string | null;
  category: string;
  details: string | null;
  status: "open" | "reviewing" | "actioned" | "rejected" | "closed";
  created_at: string;
  updated_at: string;
};

type AuditLogRow = {
  id: string;
  admin_user_id: string;
  target_user_id: string | null;
  action: string;
  reason: string | null;
  created_at: string;
};

type MfaEnrollment = {
  factorId: string;
  qrCode: string;
  secret: string;
};

type MfaChallenge = {
  factorId: string;
  challengeId: string;
};

function formatDate(value?: string | null): string {
  if (!value) return "-";
  return new Intl.DateTimeFormat("ja-JP", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(new Date(value));
}

function statusText(status: string): string {
  switch (status) {
    case "sent":
      return "送信済み";
    case "delivered":
      return "配達済み";
    case "delivery_delayed":
      return "配送遅延";
    case "bounced":
      return "拒否";
    case "complained":
      return "迷惑報告";
    case "pending":
      return "送信中";
    case "failed":
      return "送信失敗";
    case "redeemed":
      return "使用済み";
    case "revoked":
      return "取消済み";
    case "expired":
      return "期限切れ";
    default:
      return status;
  }
}

function deliveryEventText(event?: string | null): string {
  switch (event) {
    case "delivered":
      return "配達済み";
    case "bounced":
      return "拒否";
    case "complained":
      return "迷惑メール報告";
    case "delivery_delayed":
      return "配送遅延";
    case "sent":
      return "Resend送信済み";
    case "queued":
      return "Resend待機中";
    case "opened":
      return "開封";
    case "clicked":
      return "クリック";
    case "failed":
      return "送信失敗";
    case "suppressed":
      return "抑止";
    default:
      return event || "未確認";
  }
}

export default function AdminPage() {
  const supabase = useMemo<SupabaseClient | null>(() => {
    const url = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const anonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY;
    if (!url || !anonKey) return null;
    return createClient(url, anonKey);
  }, []);
  const [session, setSession] = useState<Session | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [targetEmail, setTargetEmail] = useState("");
  const [note, setNote] = useState("");
  const [operationReason, setOperationReason] = useState("");
  const [expiresInDays, setExpiresInDays] = useState(7);
  const [codes, setCodes] = useState<PromoCodeRow[]>([]);
  const [users, setUsers] = useState<UserSearchRow[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [sendResult, setSendResult] = useState<SendResult | null>(null);
  const [supportRequests, setSupportRequests] = useState<SupportRequestRow[]>([]);
  const [reports, setReports] = useState<ModerationReportRow[]>([]);
  const [auditLogs, setAuditLogs] = useState<AuditLogRow[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [mfaLevel, setMfaLevel] = useState<"loading" | "aal1" | "aal2" | "unavailable">("loading");
  const [hasVerifiedTotp, setHasVerifiedTotp] = useState(false);
  const [mfaEnrollment, setMfaEnrollment] = useState<MfaEnrollment | null>(null);
  const [mfaEnrollmentCode, setMfaEnrollmentCode] = useState("");
  const [mfaChallenge, setMfaChallenge] = useState<MfaChallenge | null>(null);
  const [mfaChallengeCode, setMfaChallengeCode] = useState("");
  const [mfaBusy, setMfaBusy] = useState(false);
  const [unverifiedTotpFactorId, setUnverifiedTotpFactorId] = useState<string | null>(null);
  const [adminRole, setAdminRole] = useState<string | null>(null);
  const sendInFlightRef = useRef(false);

  const authHeaders = useMemo<Record<string, string>>(
    () => {
      const headers: Record<string, string> = {};
      if (session?.access_token) {
        headers.Authorization = `Bearer ${session.access_token}`;
      }
      return headers;
    },
    [session],
  );

  useEffect(() => {
    if (!supabase) return;
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, nextSession) => {
      setSession(nextSession);
    });
    return () => data.subscription.unsubscribe();
  }, [supabase]);

  async function signIn(event: FormEvent) {
    event.preventDefault();
    setError("");
    if (!supabase) {
      setError("Supabase公開設定が不足しています");
      return;
    }
    const { error: signInError } = await supabase.auth.signInWithPassword({ email, password });
    if (signInError) {
      setError(signInError.message);
    }
  }

  async function refreshMfaLevel() {
    if (!supabase || !session) {
      setMfaLevel("unavailable");
      setHasVerifiedTotp(false);
      return;
    }
    const [{ data, error: mfaError }, { data: factors }] = await Promise.all([
      supabase.auth.mfa.getAuthenticatorAssuranceLevel(),
      supabase.auth.mfa.listFactors(),
    ]);
    setHasVerifiedTotp(Boolean(factors?.totp?.some((factor) => factor.status === "verified")));
    if (mfaError || !data.currentLevel) {
      setMfaLevel("unavailable");
      return;
    }
    setMfaLevel(data.currentLevel);
  }

  async function beginMfaChallenge() {
    if (!supabase || !session) return;
    setError("");
    const { data: factors, error: factorsError } = await supabase.auth.mfa.listFactors();
    const factor = factors?.totp?.find((candidate) => candidate.status === "verified");
    if (factorsError || !factor) {
      await beginMfaEnrollment();
      return;
    }
    const { data: challenge, error: challengeError } = await supabase.auth.mfa.challenge({ factorId: factor.id });
    if (challengeError || !challenge) {
      setError("追加認証チャレンジを開始できませんでした");
      return;
    }
    setMfaChallenge({ factorId: factor.id, challengeId: challenge.id });
    setMfaChallengeCode("");
    setMessage("認証アプリの6桁コードを入力してください。");
  }

  async function completeMfaChallenge() {
    if (!supabase || !session || !mfaChallenge || mfaBusy) return;
    const code = mfaChallengeCode.replace(/\s/g, "");
    if (!/^\d{6}$/.test(code)) {
      setError("認証コードは6桁の数字で入力してください");
      return;
    }
    setMfaBusy(true);
    setError("");
    const { error: verifyError } = await supabase.auth.mfa.verify({
      factorId: mfaChallenge.factorId,
      challengeId: mfaChallenge.challengeId,
      code,
    });
    if (verifyError) {
      setError("追加認証コードを確認できませんでした");
      setMfaBusy(false);
      return;
    }
    const { data: refreshed } = await supabase.auth.refreshSession();
    if (refreshed.session) setSession(refreshed.session);
    setMfaChallenge(null);
    setMfaChallengeCode("");
    setMessage("追加認証を確認しました。高リスク操作を実行できます。");
    await refreshMfaLevel();
    setMfaBusy(false);
  }

  async function beginMfaEnrollment() {
    if (!supabase || !session || mfaBusy) return;
    setMfaBusy(true);
    setError("");
    const { data: factors, error: factorsError } = await supabase.auth.mfa.listFactors();
    if (factorsError) {
      setError("TOTP登録状態を確認できませんでした");
      setMfaBusy(false);
      return;
    }
    const unverified = factors?.all?.find((factor) => factor.factor_type === "totp" && factor.status === "unverified");
    if (unverified) {
      setUnverifiedTotpFactorId(unverified.id);
      setError("未確認のTOTP登録が残っています。不要なら破棄して登録をやり直せます。");
      setMfaBusy(false);
      return;
    }
    const { data, error: enrollError } = await supabase.auth.mfa.enroll({
      factorType: "totp",
      issuer: "りんばむ管理画面",
      friendlyName: "りんばむ管理画面 TOTP",
    });
    if (enrollError || !data || data.type !== "totp") {
      setError("TOTP登録を開始できませんでした");
      setMfaBusy(false);
      return;
    }
    setMfaEnrollment({ factorId: data.id, qrCode: data.totp.qr_code, secret: data.totp.secret });
    setMfaEnrollmentCode("");
    setMessage("QRコードを認証アプリに登録し、表示された6桁コードを入力してください。");
    setMfaBusy(false);
  }

  async function discardUnverifiedMfa() {
    if (!supabase || !unverifiedTotpFactorId || mfaBusy) return;
    setMfaBusy(true);
    setError("");
    const { error: unenrollError } = await supabase.auth.mfa.unenroll({ factorId: unverifiedTotpFactorId });
    if (unenrollError) {
      setError("未確認のTOTP登録を破棄できませんでした");
      setMfaBusy(false);
      return;
    }
    setUnverifiedTotpFactorId(null);
    setMessage("未確認のTOTP登録を破棄しました。新しい登録を開始できます。");
    setMfaBusy(false);
    await beginMfaEnrollment();
  }

  async function completeMfaEnrollment() {
    if (!supabase || !session || !mfaEnrollment || mfaBusy) return;
    const code = mfaEnrollmentCode.replace(/\s/g, "");
    if (!/^\d{6}$/.test(code)) {
      setError("認証コードは6桁の数字で入力してください");
      return;
    }
    setMfaBusy(true);
    setError("");
    const { data: challenge, error: challengeError } = await supabase.auth.mfa.challenge({ factorId: mfaEnrollment.factorId });
    if (challengeError || !challenge) {
      setError("TOTP登録の確認チャレンジを開始できませんでした");
      setMfaBusy(false);
      return;
    }
    const { error: verifyError } = await supabase.auth.mfa.verify({
      factorId: mfaEnrollment.factorId,
      challengeId: challenge.id,
      code,
    });
    if (verifyError) {
      setError("TOTP登録コードを確認できませんでした");
      setMfaBusy(false);
      return;
    }
    const { data: refreshed } = await supabase.auth.refreshSession();
    if (refreshed.session) setSession(refreshed.session);
    setMfaEnrollment(null);
    setMfaEnrollmentCode("");
    setMessage("TOTPを登録し、追加認証を確認しました。");
    await refreshMfaLevel();
    setMfaBusy(false);
  }

  function requiredOperationReason(): string | null {
    if (mfaLevel !== "aal2") {
      setError("高リスク操作にはAAL2/TOTPの追加認証が必要です");
      return null;
    }
    const reason = operationReason.trim();
    if (!reason) {
      setError("管理操作の理由は必須です");
      return null;
    }
    return reason.slice(0, 1000);
  }

  async function fetchCodes() {
    if (!session) return;
    setError("");
    const response = await fetch("/api/admin/promo-codes", { headers: authHeaders });
    const body = await response.json();
    if (!response.ok) {
      setError(body.error ?? "優待コード一覧を取得できませんでした");
      return;
    }
    setCodes(body.codes ?? []);
  }

  async function fetchOperations() {
    if (!session) return;
    const [supportResponse, moderationResponse, auditResponse] = await Promise.all([
      fetch("/api/admin/support", { headers: authHeaders }),
      fetch("/api/admin/moderation", { headers: authHeaders }),
      fetch("/api/admin/audit", { headers: authHeaders }),
    ]);
    const [supportBody, moderationBody, auditBody] = await Promise.all([
      supportResponse.json(),
      moderationResponse.json(),
      auditResponse.json(),
    ]);
    if (supportResponse.ok) setSupportRequests(supportBody.requests ?? []);
    if (moderationResponse.ok) setReports(moderationBody.reports ?? []);
    if (auditResponse.ok) setAuditLogs(auditBody.logs ?? []);
    const failed = [supportResponse, moderationResponse, auditResponse].find((response) => !response.ok);
    if (failed) setError("管理運用データの一部を取得できませんでした");
  }

  async function updateSupport(requestId: string, supportStatus: SupportRequestRow["support_status"]) {
    if (!session) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const response = await fetch("/api/admin/support", {
      method: "PATCH",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ id: requestId, supportStatus, reason }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "サポート状態を更新できませんでした");
      return;
    }
    setMessage("サポート状態を更新しました");
    await fetchOperations();
  }

  async function moderateReport(reportId: string, action: string) {
    if (!session) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const response = await fetch("/api/admin/moderation", {
      method: "PATCH",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ reportId, action, reason }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "モデレーションに失敗しました");
      return;
    }
    setMessage("通報状態を更新しました");
    await fetchOperations();
  }

  async function searchUsers(value: string) {
    setTargetEmail(value);
    if (!session || value.trim().length < 2) {
      setUsers([]);
      return;
    }
    const response = await fetch(`/api/admin/users?q=${encodeURIComponent(value)}`, { headers: authHeaders });
    const body = await response.json();
    if (response.ok) {
      setUsers(body.users ?? []);
    }
  }

  async function sendCode(event: FormEvent) {
    event.preventDefault();
    if (!session || sendInFlightRef.current) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    sendInFlightRef.current = true;
    setIsLoading(true);
    setError("");
    setMessage("");
    setSendResult(null);
    try {
      const operationId = crypto.randomUUID();
      const response = await fetch("/api/admin/promo-codes/send", {
        method: "POST",
        headers: {
          ...authHeaders,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ targetEmail, note, expiresInDays, reason, operationId }),
      });
      const body = await response.json();
      if (!response.ok) {
        setError(body.error ?? "優待コードを送信できませんでした");
        return;
      }
      setSendResult(body);
      setMessage("優待コードを送信しました。コードはこの画面を離れると再表示できません。");
      setNote("");
      setOperationReason("");
      await fetchCodes();
    } finally {
      sendInFlightRef.current = false;
      setIsLoading(false);
    }
  }

  async function revokeCode(id: string) {
    if (!session || !window.confirm("この優待コードを取り消しますか？")) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const response = await fetch(`/api/admin/promo-codes/${id}/revoke`, {
      method: "POST",
      headers: {
        ...authHeaders,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ reason }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "取り消しに失敗しました");
      return;
    }
    setMessage("優待コードを取り消しました");
    await fetchCodes();
  }

  async function fetchAdminIdentity() {
    if (!session) return;
    const response = await fetch("/api/admin/me", { headers: authHeaders });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "管理者ロールを取得できませんでした");
      return;
    }
    setAdminRole(typeof body.admin?.role === "string" ? body.admin.role : null);
  }

  useEffect(() => {
    if (session) {
      fetchAdminIdentity();
      fetchCodes();
      fetchOperations();
      refreshMfaLevel();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  if (!session) {
    return (
      <main className="shell narrow">
        <section className="panel">
          <h1>URL Saver 管理</h1>
          <p className="muted">優待コード管理には管理者アカウントでのサインインが必要です。</p>
          <form onSubmit={signIn} className="stack">
            <label>
              メール
              <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" autoComplete="email" />
            </label>
            <label>
              パスワード
              <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" />
            </label>
            {error && <p className="error">{error}</p>}
            <button type="submit">サインイン</button>
          </form>
        </section>
      </main>
    );
  }

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">URL Saver Admin</p>
          <h1>優待コード管理</h1>
          <p className="muted" data-testid="admin-role">
            role: {adminRole ?? "管理者ロールを確認中"}
          </p>
        </div>
        <button className="secondary" onClick={() => supabase?.auth.signOut()}>サインアウト</button>
      </header>

      <section className="panel stack" data-testid="admin-step-up">
        <div>
          <h2>管理者追加認証</h2>
          <p className="muted" data-testid="admin-step-up-status">
            {mfaLevel === "aal2" ? "AAL2/TOTP確認済み" : mfaLevel === "aal1" ? "AAL1です。高リスク操作には追加認証が必要です。" : "追加認証状態を確認できません。"}
          </p>
        </div>
        <label>
          操作理由（高リスク操作共通・必須）
          <textarea
            value={operationReason}
            onChange={(event) => setOperationReason(event.target.value)}
            rows={2}
            required
            data-testid="admin-operation-reason"
          />
        </label>
        {mfaLevel !== "aal2" && !mfaEnrollment && !mfaChallenge && !unverifiedTotpFactorId && (
          <button className="secondary" onClick={beginMfaChallenge} disabled={mfaBusy}>
            {hasVerifiedTotp ? "TOTPで追加認証" : "TOTPを登録"}
          </button>
        )}
        {unverifiedTotpFactorId && (
          <div className="stack">
            <p className="muted">前回中断した未確認のTOTP登録があります。</p>
            <button className="secondary" onClick={discardUnverifiedMfa} disabled={mfaBusy}>
              未確認のTOTP登録を破棄してやり直す
            </button>
          </div>
        )}
        {mfaChallenge && (
          <form className="stack" onSubmit={(event) => { event.preventDefault(); void completeMfaChallenge(); }}>
            <label>
              認証アプリの6桁コード
              <input
                value={mfaChallengeCode}
                onChange={(event) => setMfaChallengeCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                required
              />
            </label>
            <button type="submit" disabled={mfaBusy}>追加認証を確認</button>
          </form>
        )}
        {mfaEnrollment && (
          <form className="stack" onSubmit={(event) => { event.preventDefault(); void completeMfaEnrollment(); }}>
            <p className="muted">認証アプリでQRコードを読み取ってください。画像を保存・共有しないでください。</p>
            <Image src={mfaEnrollment.qrCode} alt="TOTP登録用QRコード" width={220} height={220} unoptimized />
            <label>
              QRコードを読めない場合の秘密鍵
              <input value={mfaEnrollment.secret} readOnly type="password" autoComplete="off" />
            </label>
            <label>
              認証アプリの6桁コード
              <input
                value={mfaEnrollmentCode}
                onChange={(event) => setMfaEnrollmentCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={6}
                required
              />
            </label>
            <button type="submit" disabled={mfaBusy}>TOTP登録を確認</button>
          </form>
        )}
      </section>

      {(message || error) && (
        <div
          className={error ? "notice error" : "notice success"}
          role={error ? "alert" : "status"}
          aria-live={error ? "assertive" : "polite"}
        >
          {error || message}
        </div>
      )}

      <section className="grid">
        <form onSubmit={sendCode} className="panel stack">
          <div>
            <h2>このユーザーに送る</h2>
            <p className="muted">メール入力でも、既存ユーザー検索から選択しても送れます。</p>
          </div>
          <label>
            宛先メール
            <input
              value={targetEmail}
              onChange={(event) => searchUsers(event.target.value)}
              type="email"
              placeholder="user@example.com"
            />
          </label>
          {users.length > 0 && (
            <div className="suggestions">
              {users.map((user) => (
                <button key={user.id} type="button" onClick={() => {
                  setTargetEmail(user.email);
                  setUsers([]);
                }}>
                  <span>{user.email}</span>
                  <small>最終ログイン {formatDate(user.lastSignInAt)}</small>
                </button>
              ))}
            </div>
          )}
          <label>
            有効期限（日）
            <input
              value={expiresInDays}
              onChange={(event) => setExpiresInDays(Number(event.target.value))}
              type="number"
              min={1}
              max={90}
            />
          </label>
          <label>
            管理メモ
            <textarea value={note} onChange={(event) => setNote(event.target.value)} rows={4} />
          </label>
          <button data-testid="admin-submit" type="submit" disabled={isLoading || mfaLevel !== "aal2"}>{isLoading ? "送信中..." : "優待コードを送る"}</button>
        </form>

        <section className="panel stack">
          <h2>送信結果</h2>
          {sendResult ? (
            <div className="result">
              <p><strong>宛先:</strong> {sendResult.targetEmail}</p>
              <p><strong>コード:</strong> <code>{sendResult.code}</code></p>
              <p><strong>リンク:</strong> <a href={sendResult.redeemLink}>{sendResult.redeemLink}</a></p>
              <p><strong>期限:</strong> {formatDate(sendResult.expiresAt)}</p>
              <p className="muted">メール本文には安全のためコード付きリンクではなく、手入力コードを送っています。</p>
              <p className="muted">生コードはDBに保存されないため、この表示後は再確認できません。</p>
            </div>
          ) : (
            <p className="muted">送信に成功すると、ここに一度だけコードとリンクが表示されます。</p>
          )}
        </section>
      </section>

      <section className="panel">
        <div className="listHeader">
          <div>
            <h2>送信済みコード</h2>
            <p className="muted">Webhookで反映された送信済み、配達済み、拒否、迷惑報告、送信失敗を確認できます。</p>
          </div>
          <button className="secondary" onClick={fetchCodes}>再読み込み</button>
        </div>
        <div className="tableWrap">
          <table>
            <thead>
              <tr>
                <th>状態</th>
                <th>宛先</th>
                <th>作成</th>
                <th>送信</th>
                <th>期限</th>
                <th>メモ/エラー</th>
                <th>最終配送</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {codes.map((row) => (
                <tr key={row.id}>
                  <td><span className={`chip ${row.status_label}`}>{statusText(row.status_label)}</span></td>
                  <td>{row.target_email}</td>
                  <td>{formatDate(row.created_at)}</td>
                  <td>{formatDate(row.sent_at)}</td>
                  <td>{formatDate(row.expires_at)}</td>
                  <td>{row.delivery_error || row.revoked_reason || row.note || "-"}</td>
                  <td>
                    <div className="deliveryCell">
                      <small className="muted">
                        {deliveryEventText(row.delivery_event_type?.replace(/^email\./, "") ?? row.delivery_status)}
                        {row.delivery_event_at ? ` / ${formatDate(row.delivery_event_at)}` : ""}
                      </small>
                    </div>
                  </td>
                  <td>
                    {!row.revoked_at && !row.claimed_at && (
                      <button className="danger" onClick={() => revokeCode(row.id)}>取消</button>
                    )}
                  </td>
                </tr>
              ))}
              {codes.length === 0 && (
                <tr>
                  <td colSpan={8} className="empty">優待コードはまだありません。</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="grid operationsGrid">
        <section className="panel">
          <div className="listHeader">
            <div>
              <h2>サポートキュー</h2>
              <p className="muted">問い合わせ受付と配送状態を確認し、対応状態を記録します。</p>
            </div>
            <button className="secondary" onClick={fetchOperations}>再読み込み</button>
          </div>
          <div className="tableWrap">
            <table>
              <thead><tr><th>受付</th><th>アプリ</th><th>配送</th><th>対応状態</th><th>作成</th></tr></thead>
              <tbody>
                {supportRequests.map((row) => (
                  <tr key={row.id}>
                    <td><strong>{row.request_id}</strong><br /><small>{row.platform} / {row.build_type}</small></td>
                    <td>{row.app_version}<br /><small>{row.is_signed_in ? "ログイン済み" : "未ログイン"}</small></td>
                    <td>{deliveryEventText(row.delivery_event_type ?? row.delivery_status)}<br /><small>{row.delivery_error ?? "-"}</small></td>
                    <td>
                      <select value={row.support_status} onChange={(event) => updateSupport(row.id, event.target.value as SupportRequestRow["support_status"])}>
                        <option value="open">未対応</option>
                        <option value="in_progress">対応中</option>
                        <option value="resolved">解決</option>
                        <option value="closed">終了</option>
                      </select>
                    </td>
                    <td>{formatDate(row.created_at)}</td>
                  </tr>
                ))}
                {supportRequests.length === 0 && <tr><td colSpan={5} className="empty">問い合わせはありません。</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <section className="panel">
          <div className="listHeader">
            <div>
              <h2>通報・モデレーション</h2>
              <p className="muted">共有コンテンツの通報を確認し、対応履歴を残します。</p>
            </div>
            <button className="secondary" onClick={fetchOperations}>再読み込み</button>
          </div>
          <div className="operationList">
            {reports.map((report) => (
              <article className="operationItem" key={report.id}>
                <div><strong>{report.category}</strong><span className="muted"> / {formatDate(report.created_at)}</span></div>
                <p>{report.details || "詳細なし"}</p>
                <div className="operationActions">
                  <span className="chip">{report.status}</span>
                  <button className="small secondary" onClick={() => moderateReport(report.id, "review")}>確認中</button>
                  <button className="small danger" onClick={() => moderateReport(report.id, "reject")}>却下</button>
                  <button className="small" onClick={() => moderateReport(report.id, "close")}>終了</button>
                </div>
              </article>
            ))}
            {reports.length === 0 && <p className="empty">未処理の通報はありません。</p>}
          </div>
        </section>
      </section>

      <section className="panel">
        <div className="listHeader">
          <div>
            <h2>管理監査ログ</h2>
            <p className="muted">優待コード、サポート、モデレーションの操作履歴です。</p>
          </div>
          <button className="secondary" onClick={fetchOperations}>再読み込み</button>
        </div>
        <div className="tableWrap">
          <table>
            <thead><tr><th>操作</th><th>管理者ID</th><th>対象ユーザーID</th><th>理由</th><th>日時</th></tr></thead>
            <tbody>
              {auditLogs.map((log) => (
                <tr key={log.id}>
                  <td>{log.action}</td><td><code>{log.admin_user_id}</code></td><td><code>{log.target_user_id ?? "-"}</code></td><td>{log.reason ?? "-"}</td><td>{formatDate(log.created_at)}</td>
                </tr>
              ))}
              {auditLogs.length === 0 && <tr><td colSpan={5} className="empty">監査ログはありません。</td></tr>}
            </tbody>
          </table>
        </div>
      </section>
    </main>
  );
}
