"use client";

import { createClient, Session, SupabaseClient } from "@supabase/supabase-js";
import { FormEvent, useEffect, useMemo, useState } from "react";

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
  const [expiresInDays, setExpiresInDays] = useState(7);
  const [codes, setCodes] = useState<PromoCodeRow[]>([]);
  const [users, setUsers] = useState<UserSearchRow[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [sendResult, setSendResult] = useState<SendResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);

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
    if (!session || isLoading) return;
    setIsLoading(true);
    setError("");
    setMessage("");
    setSendResult(null);
    try {
      const response = await fetch("/api/admin/promo-codes/send", {
        method: "POST",
        headers: {
          ...authHeaders,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ targetEmail, note, expiresInDays }),
      });
      const body = await response.json();
      if (!response.ok) {
        setError(body.error ?? "優待コードを送信できませんでした");
        return;
      }
      setSendResult(body);
      setMessage("優待コードを送信しました。コードはこの画面を離れると再表示できません。");
      setNote("");
      await fetchCodes();
    } finally {
      setIsLoading(false);
    }
  }

  async function revokeCode(id: string) {
    if (!session || !window.confirm("この優待コードを取り消しますか？")) return;
    const response = await fetch(`/api/admin/promo-codes/${id}/revoke`, {
      method: "POST",
      headers: {
        ...authHeaders,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ reason: "admin_revoked" }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "取り消しに失敗しました");
      return;
    }
    setMessage("優待コードを取り消しました");
    await fetchCodes();
  }

  useEffect(() => {
    if (session) {
      fetchCodes();
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
        </div>
        <button className="secondary" onClick={() => supabase?.auth.signOut()}>サインアウト</button>
      </header>

      {(message || error) && (
        <div className={error ? "notice error" : "notice success"}>{error || message}</div>
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
          <button type="submit" disabled={isLoading}>{isLoading ? "送信中..." : "優待コードを送る"}</button>
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
    </main>
  );
}
