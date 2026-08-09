"use client";

import { createClient, Session, SupabaseClient } from "@supabase/supabase-js";
import Image from "next/image";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { createDebouncedSearchController, USER_SEARCH_DEBOUNCE_MS } from "@/lib/debounced-search";

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

type UserDirectoryRow = {
  id: string;
  email: string;
  displayName: string | null;
  createdAt: string;
  lastSignInAt: string | null;
  lastSeenAt: string | null;
  currentPlan: string;
  accountStatus: string;
};

type EntitlementGrantRow = {
  id: string;
  plan: string;
  source: string;
  storePlatform: string | null;
  startsAt: string;
  expiresAt: string | null;
  status: string;
  createdAt: string;
};

type UserDetail = UserDirectoryRow & {
  emailConfirmedAt: string | null;
  authProvider: string | null;
  adminNote: string | null;
  supportTicketId: string | null;
  entitlementGrants: EntitlementGrantRow[];
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
  operation_id: string | null;
  phase: "started" | "completed" | "failed";
  assurance: {
    aal?: string;
    methods?: string[];
    verifiedAt?: number | null;
  } | null;
  created_at: string;
};

type AdminCapability =
  | "promos.read"
  | "promos.issue"
  | "support.read"
  | "support.write"
  | "moderation.read"
  | "moderation.manage"
  | "users.search"
  | "users.read"
  | "users.manage"
  | "admins.manage"
  | "audit.read";

type PendingPromoOperation = {
  id: string;
  fingerprint: string;
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

function formatCompactDate(value?: string | null, includeTime = false): string {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "-";

  const isCurrentYear = date.getFullYear() === new Date().getFullYear();
  return new Intl.DateTimeFormat("ja-JP", {
    ...(isCurrentYear ? {} : { year: "2-digit" }),
    month: "numeric",
    day: "numeric",
    ...(includeTime ? { hour: "2-digit", minute: "2-digit", hour12: false } : {}),
  }).format(date);
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

const USER_PAGE_SIZE = 50;

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
  const [directoryUsers, setDirectoryUsers] = useState<UserDirectoryRow[]>([]);
  const [directoryTotal, setDirectoryTotal] = useState(0);
  const [directoryOffset, setDirectoryOffset] = useState(0);
  const [directorySearch, setDirectorySearch] = useState("");
  const [directoryStatus, setDirectoryStatus] = useState("");
  const [directoryLoading, setDirectoryLoading] = useState(false);
  const [selectedUser, setSelectedUser] = useState<UserDetail | null>(null);
  const [userAdminNote, setUserAdminNote] = useState("");
  const [grantPlan, setGrantPlan] = useState("standard");
  const [grantExpiresInDays, setGrantExpiresInDays] = useState(30);
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
  const [adminCapabilities, setAdminCapabilities] = useState<AdminCapability[]>([]);
  const [stepUpExpiresAt, setStepUpExpiresAt] = useState<number | null>(null);
  const [stepUpClock, setStepUpClock] = useState(() => Math.floor(Date.now() / 1000));
  const sendInFlightRef = useRef(false);
  const pendingPromoOperationRef = useRef<PendingPromoOperation | null>(null);
  const pendingAdminOperationsRef = useRef(new Map<string, string>());
  const userSearchController = useMemo(
    () => createDebouncedSearchController<UserSearchRow[]>(
      USER_SEARCH_DEBOUNCE_MS,
      (results) => setUsers(results),
      () => setError("ユーザー検索に失敗しました"),
    ),
    [],
  );

  const hasCapability = (capability: AdminCapability) => adminCapabilities.includes(capability);
  const hasHighRiskCapability = hasCapability("promos.issue") || hasCapability("support.write") || hasCapability("moderation.manage") || hasCapability("users.manage");
  const highRiskReady = mfaLevel === "aal2" && stepUpExpiresAt !== null && stepUpClock <= stepUpExpiresAt;

  useEffect(() => {
    if (stepUpExpiresAt === null) return;
    const delay = Math.max(0, (stepUpExpiresAt - Math.floor(Date.now() / 1000)) * 1000 + 250);
    const timer = window.setTimeout(() => setStepUpClock(Math.floor(Date.now() / 1000)), Math.min(delay, 2_147_000_000));
    return () => window.clearTimeout(timer);
  }, [stepUpExpiresAt]);

  useEffect(() => () => userSearchController.cancel(), [userSearchController]);

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
    if (refreshed.session) {
      setSession(refreshed.session);
      await fetchAdminIdentity(refreshed.session.access_token);
    }
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
    if (refreshed.session) {
      setSession(refreshed.session);
      await fetchAdminIdentity(refreshed.session.access_token);
    }
    setMfaEnrollment(null);
    setMfaEnrollmentCode("");
    setMessage("TOTPを登録し、追加認証を確認しました。");
    await refreshMfaLevel();
    setMfaBusy(false);
  }

  function requiredOperationReason(): string | null {
    if (!highRiskReady) {
      setStepUpExpiresAt(null);
      setError("高リスク操作には有効なAAL2/TOTPの追加認証が必要です。もう一度追加認証してください。");
      return null;
    }
    const reason = operationReason.trim();
    if (!reason) {
      setError("管理操作の理由は必須です");
      return null;
    }
    return reason.slice(0, 1000);
  }

  function handleStepUpResponse(response: Response, body: Record<string, unknown>): boolean {
    if (response.status !== 428) return false;
    setStepUpExpiresAt(null);
    setStepUpClock(Math.floor(Date.now() / 1000));
    setError(typeof body.error === "string" ? body.error : "追加認証が期限切れです。もう一度TOTPで追加認証してください。");
    return true;
  }

  function operationIdFor(key: string): string {
    const existing = pendingAdminOperationsRef.current.get(key);
    if (existing) return existing;
    const operationId = crypto.randomUUID();
    pendingAdminOperationsRef.current.set(key, operationId);
    return operationId;
  }

  function completeOperation(key: string) {
    pendingAdminOperationsRef.current.delete(key);
  }

  async function fetchCodes(
    capabilities: readonly AdminCapability[] = adminCapabilities,
    headers: Record<string, string> = authHeaders,
  ) {
    if (!session || !capabilities.includes("promos.read")) {
      setCodes([]);
      return;
    }
    setError("");
    const response = await fetch("/api/admin/promo-codes", { headers });
    const body = await response.json();
    if (!response.ok) {
      setError(body.error ?? "優待コード一覧を取得できませんでした");
      return;
    }
    setCodes(body.codes ?? []);
  }

  async function fetchOperations(
    capabilities: readonly AdminCapability[] = adminCapabilities,
    headers: Record<string, string> = authHeaders,
  ) {
    if (!session) return;
    const endpoints: Array<{ kind: "support" | "moderation" | "audit"; path: string }> = [];
    if (capabilities.includes("support.read")) endpoints.push({ kind: "support", path: "/api/admin/support" });
    else setSupportRequests([]);
    if (capabilities.includes("moderation.read")) endpoints.push({ kind: "moderation", path: "/api/admin/moderation" });
    else setReports([]);
    if (capabilities.includes("audit.read")) endpoints.push({ kind: "audit", path: "/api/admin/audit" });
    else setAuditLogs([]);

    const results = await Promise.all(endpoints.map(async (endpoint) => {
      const response = await fetch(endpoint.path, { headers });
      const body = await response.json().catch(() => ({}));
      return { ...endpoint, response, body };
    }));
    let failed = false;
    for (const result of results) {
      if (!result.response.ok) {
        failed = true;
        continue;
      }
      if (result.kind === "support") setSupportRequests(result.body.requests ?? []);
      if (result.kind === "moderation") setReports(result.body.reports ?? []);
      if (result.kind === "audit") setAuditLogs(result.body.logs ?? []);
    }
    if (failed) setError("許可された管理運用データの一部を取得できませんでした");
  }

  async function updateSupport(row: SupportRequestRow, supportStatus: SupportRequestRow["support_status"]) {
    if (!session) return;
    if (supportStatus === row.support_status) return;
    const labels: Record<SupportRequestRow["support_status"], string> = {
      open: "未対応",
      in_progress: "対応中",
      resolved: "解決",
      closed: "終了",
    };
    if (!highRiskReady || !window.confirm(
      `受付 ${row.request_id} の対応状態を「${labels[row.support_status]}」から「${labels[supportStatus]}」へ変更しますか？\n\nこの操作は監査ログに記録されます。`,
    )) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const operationKey = `support:${row.id}:${supportStatus}:${reason}`;
    const operationId = operationIdFor(operationKey);
    const response = await fetch("/api/admin/support", {
      method: "PATCH",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ id: row.id, supportStatus, reason, operationId }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (handleStepUpResponse(response, body)) return;
      if (response.status === 409 && body.error === "duplicate_operation") {
        completeOperation(operationKey);
        setMessage("同じサポート操作はすでに処理されています。現在状態を再取得しました。");
        await fetchOperations();
        return;
      }
      setError(body.error ?? "サポート状態を更新できませんでした");
      return;
    }
    completeOperation(operationKey);
    setMessage("サポート状態を更新しました");
    await fetchOperations();
  }

  async function moderateReport(reportId: string, action: string) {
    if (!session) return;
    const actionLabels: Record<string, string> = { review: "確認中", reject: "却下", close: "終了" };
    const actionLabel = actionLabels[action] ?? action;
    if (!highRiskReady || !window.confirm(
      `通報 ${reportId} に「${actionLabel}」を適用しますか？\n\n対象と操作を確認してください。この操作は監査ログに記録されます。`,
    )) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const operationKey = `moderation:${reportId}:${action}:${reason}`;
    const operationId = operationIdFor(operationKey);
    const response = await fetch("/api/admin/moderation", {
      method: "PATCH",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ reportId, action, reason, operationId }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (handleStepUpResponse(response, body)) return;
      if (response.status === 409 && body.error === "duplicate_operation") {
        completeOperation(operationKey);
        setMessage("同じモデレーション操作はすでに処理されています。現在状態を再取得しました。");
        await fetchOperations();
        return;
      }
      setError(body.error ?? "モデレーションに失敗しました");
      return;
    }
    completeOperation(operationKey);
    setMessage("通報状態を更新しました");
    await fetchOperations();
  }

  function searchUsers(value: string) {
    setTargetEmail(value);
    if (!session || !hasCapability("users.search") || value.trim().length < 2) {
      userSearchController.cancel();
      setUsers([]);
      return;
    }
    const query = value.trim();
    userSearchController.schedule(async (signal) => {
      const response = await fetch(`/api/admin/users?q=${encodeURIComponent(query)}`, {
        headers: authHeaders,
        signal,
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error("user_search_failed");
      return Array.isArray(body.users) ? body.users as UserSearchRow[] : [];
    });
  }

  async function fetchUserDirectory(
    capabilities: readonly AdminCapability[] = adminCapabilities,
    headers: Record<string, string> = authHeaders,
    offset: number = directoryOffset,
  ) {
    if (!session || !capabilities.includes("users.read")) {
      setDirectoryUsers([]);
      setDirectoryTotal(0);
      setSelectedUser(null);
      return;
    }
    setDirectoryLoading(true);
    setError("");
    const params = new URLSearchParams({
      mode: "directory",
      q: directorySearch.trim(),
      status: directoryStatus,
      limit: String(USER_PAGE_SIZE),
      offset: String(offset),
    });
    try {
      const response = await fetch(`/api/admin/users?${params.toString()}`, { headers });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        setError(body.error ?? "ユーザー一覧を取得できませんでした");
        return;
      }
      setDirectoryUsers(body.users ?? []);
      setDirectoryTotal(Number(body.total ?? 0));
      setDirectoryOffset(Number(body.offset ?? offset));
    } finally {
      setDirectoryLoading(false);
    }
  }

  async function submitUserDirectorySearch(event: FormEvent) {
    event.preventDefault();
    setSelectedUser(null);
    await fetchUserDirectory(adminCapabilities, authHeaders, 0);
  }

  async function fetchUserDetail(userId: string) {
    setDirectoryLoading(true);
    setError("");
    try {
      const response = await fetch(`/api/admin/users/${encodeURIComponent(userId)}`, { headers: authHeaders });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        setError(body.error ?? "ユーザー詳細を取得できませんでした");
        return;
      }
      setSelectedUser(body.user ?? null);
      setUserAdminNote(typeof body.user?.adminNote === "string" ? body.user.adminNote : "");
    } finally {
      setDirectoryLoading(false);
    }
  }

  async function manageUser(action: string, payload: Record<string, unknown> = {}) {
    if (!selectedUser) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const operationKey = `user:${selectedUser.id}:${action}:${JSON.stringify(payload)}:${reason}`;
    const operationId = operationIdFor(operationKey);
    const response = await fetch(`/api/admin/users/${encodeURIComponent(selectedUser.id)}`, {
      method: "PATCH",
      headers: { ...authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ action, reason, operationId, ...payload }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (handleStepUpResponse(response, body)) return;
      if (response.status === 409 && body.error === "duplicate_operation") {
        completeOperation(operationKey);
        setMessage("同じユーザー管理操作はすでに処理されています。現在状態を再取得しました。");
        await fetchUserDetail(selectedUser.id);
        return;
      }
      setError(body.error ?? "ユーザー管理操作に失敗しました");
      return;
    }
    completeOperation(operationKey);
    setMessage("ユーザー情報を更新しました");
    await Promise.all([
      fetchUserDetail(selectedUser.id),
      fetchUserDirectory(adminCapabilities, authHeaders, directoryOffset),
      fetchOperations(),
    ]);
  }

  function downloadVisibleUsersCsv() {
    const escapeCsv = (value: string) => `"${value.replaceAll('"', '""')}"`;
    const rows = [
      ["メール", "名前", "作成日", "最終利用", "プラン", "状態", "ユーザーID"],
      ...directoryUsers.map((user) => [
        user.email,
        user.displayName ?? "",
        user.createdAt,
        user.lastSeenAt ?? user.lastSignInAt ?? "",
        user.currentPlan,
        user.accountStatus,
        user.id,
      ]),
    ];
    const csv = `\uFEFF${rows.map((row) => row.map((value) => escapeCsv(String(value))).join(",")).join("\r\n")}`;
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = `rinbam-users-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
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
      const fingerprint = JSON.stringify({ targetEmail: targetEmail.trim().toLowerCase(), note: note.trim(), expiresInDays, reason });
      if (!pendingPromoOperationRef.current || pendingPromoOperationRef.current.fingerprint !== fingerprint) {
        pendingPromoOperationRef.current = { id: crypto.randomUUID(), fingerprint };
      }
      const operationId = pendingPromoOperationRef.current.id;
      const response = await fetch("/api/admin/promo-codes/send", {
        method: "POST",
        headers: {
          ...authHeaders,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ targetEmail, note, expiresInDays, reason, operationId }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        if (handleStepUpResponse(response, body)) return;
        if (response.status === 409 && body.error === "duplicate_operation") {
          const status = typeof body.operation?.status === "string" ? body.operation.status : "unknown";
          setMessage(`同じ優待コード送信操作はすでに受付済みです（状態: ${status}）。再送せず一覧を確認してください。`);
          await fetchCodes();
          return;
        }
        setError(body.error ?? "優待コードを送信できませんでした");
        return;
      }
      pendingPromoOperationRef.current = null;
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
    const code = codes.find((row) => row.id === id);
    if (!session || !highRiskReady || !window.confirm(
      `優待コードを取り消しますか？\n\n対象: ${code?.target_email ?? "不明"}\nコードID: ${id}\nこの操作は元に戻せず、監査ログに記録されます。`,
    )) return;
    const reason = requiredOperationReason();
    if (!reason) return;
    const operationKey = `promo-revoke:${id}:${reason}`;
    const operationId = operationIdFor(operationKey);
    const response = await fetch(`/api/admin/promo-codes/${id}/revoke`, {
      method: "POST",
      headers: {
        ...authHeaders,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ reason, operationId }),
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (handleStepUpResponse(response, body)) return;
      if (response.status === 409 && body.error === "duplicate_operation") {
        completeOperation(operationKey);
        setMessage("同じ取消操作はすでに処理されています。現在状態を再取得しました。");
        await fetchCodes();
        return;
      }
      setError(body.error ?? "取り消しに失敗しました");
      return;
    }
    completeOperation(operationKey);
    setMessage("優待コードを取り消しました");
    await fetchCodes();
  }

  async function fetchAdminIdentity(accessToken: string = session?.access_token ?? "") {
    if (!accessToken) return;
    const headers = { Authorization: `Bearer ${accessToken}` };
    const response = await fetch("/api/admin/me", { headers });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      setError(body.error ?? "管理者ロールを取得できませんでした");
      return;
    }
    const capabilities = Array.isArray(body.admin?.capabilities)
      ? body.admin.capabilities.filter((value: unknown): value is AdminCapability => typeof value === "string")
      : [];
    const expiresAt = typeof body.admin?.stepUpExpiresAt === "number" ? body.admin.stepUpExpiresAt : null;
    setAdminRole(typeof body.admin?.role === "string" ? body.admin.role : null);
    setAdminCapabilities(capabilities);
    setStepUpExpiresAt(expiresAt);
    setStepUpClock(Math.floor(Date.now() / 1000));
    await Promise.all([
      fetchCodes(capabilities, headers),
      fetchOperations(capabilities, headers),
      fetchUserDirectory(capabilities, headers, 0),
    ]);
  }

  useEffect(() => {
    if (session) {
      fetchAdminIdentity();
      refreshMfaLevel();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  if (!session) {
    return (
      <main className="shell narrow">
        <section className="panel">
          <h1>りんばむ 管理</h1>
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
          <p className="eyebrow">Rinbam Admin</p>
          <h1>管理運用</h1>
          <p className="muted" data-testid="admin-role">
            role: {adminRole ?? "管理者ロールを確認中"}
          </p>
        </div>
        <button className="secondary" onClick={() => supabase?.auth.signOut()}>サインアウト</button>
      </header>

      {hasHighRiskCapability && <section className="panel stack" data-testid="admin-step-up">
        <div>
          <h2>管理者追加認証</h2>
          <p className="muted" data-testid="admin-step-up-status">
            {highRiskReady
              ? `AAL2/TOTP確認済み（${formatDate(new Date((stepUpExpiresAt ?? 0) * 1000).toISOString())}まで）`
              : mfaLevel === "aal2"
                ? "AAL2ですが追加認証の有効期限が切れています。TOTPで再確認してください。"
                : mfaLevel === "aal1"
                  ? "AAL1です。高リスク操作には追加認証が必要です。"
                  : "追加認証状態を確認できません。"}
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
        {!highRiskReady && !mfaEnrollment && !mfaChallenge && !unverifiedTotpFactorId && (
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
      </section>}

      {(message || error) && (
        <div
          className={error ? "notice error" : "notice success"}
          role={error ? "alert" : "status"}
          aria-live={error ? "assertive" : "polite"}
        >
          {error || message}
        </div>
      )}

      {hasCapability("users.read") && <section className="panel userDirectory" data-testid="user-directory">
        <div className="listHeader">
          <div>
            <h2>ユーザー一覧</h2>
            <p className="muted">登録ユーザーの基本情報、利用状況、現在プランを所有者権限で確認します。</p>
          </div>
          <div className="userCount" aria-live="polite">
            <strong>{directoryTotal.toLocaleString("ja-JP")}</strong>
            <span>ユーザー</span>
          </div>
        </div>

        {selectedUser ? (
          <div className="userDetail">
            <div className="listHeader">
              <div>
                <button className="small secondary" onClick={() => setSelectedUser(null)}>← 一覧に戻る</button>
                <h2>{selectedUser.displayName || "名前未設定"}</h2>
                <p className="muted">{selectedUser.email}</p>
              </div>
              <span className={`chip ${selectedUser.accountStatus}`}>{selectedUser.accountStatus}</span>
            </div>
            <dl className="detailGrid">
              <div><dt>ユーザーID</dt><dd><code>{selectedUser.id}</code></dd></div>
              <div><dt>認証方式</dt><dd>{selectedUser.authProvider || "-"}</dd></div>
              <div><dt>登録日</dt><dd>{formatDate(selectedUser.createdAt)}</dd></div>
              <div><dt>メール確認</dt><dd>{formatDate(selectedUser.emailConfirmedAt)}</dd></div>
              <div><dt>最終ログイン</dt><dd>{formatDate(selectedUser.lastSignInAt)}</dd></div>
              <div><dt>最終利用</dt><dd>{formatDate(selectedUser.lastSeenAt)}</dd></div>
              <div><dt>サポートID</dt><dd>{selectedUser.supportTicketId || "-"}</dd></div>
              <div><dt>管理メモ</dt><dd>{selectedUser.adminNote || "-"}</dd></div>
            </dl>
            {hasCapability("users.manage") && <section className="userManagement">
              <h3>ユーザー管理</h3>
              <p className="muted">すべての操作に有効なTOTP追加認証と操作理由が必要です。</p>
              <div className="managementGrid">
                <div className="managementCard">
                  <h4>利用状態</h4>
                  <div className="operationActions">
                    <button
                      className="small secondary"
                      disabled={!highRiskReady || selectedUser.accountStatus === "active"}
                      onClick={() => window.confirm(`ユーザーを有効化しますか？\n\n対象: ${selectedUser.email}\nユーザーID: ${selectedUser.id}`) && void manageUser("set_status", { accountStatus: "active" })}
                    >有効化</button>
                    <button
                      className="small danger"
                      disabled={!highRiskReady || selectedUser.accountStatus === "suspended"}
                      onClick={() => window.confirm(`ユーザーを停止しますか？\n\n対象: ${selectedUser.email}\nユーザーID: ${selectedUser.id}\nこの操作は利用可否に影響し、監査ログに記録されます。`) && void manageUser("set_status", { accountStatus: "suspended" })}
                    >停止</button>
                    <button
                      className="small danger"
                      disabled={!highRiskReady || selectedUser.accountStatus === "banned"}
                      onClick={() => window.confirm(`ユーザーをBANしますか？\n\n対象: ${selectedUser.email}\nユーザーID: ${selectedUser.id}\nこの操作は利用可否に影響し、監査ログに記録されます。`) && void manageUser("set_status", { accountStatus: "banned" })}
                    >BAN</button>
                  </div>
                </div>
                <div className="managementCard">
                  <h4>管理メモ</h4>
                  <textarea value={userAdminNote} onChange={(event) => setUserAdminNote(event.target.value)} maxLength={2000} rows={3} />
                  <button className="small" disabled={!highRiskReady} onClick={() => void manageUser("update_note", { adminNote: userAdminNote })}>メモを保存</button>
                </div>
                <div className="managementCard">
                  <h4>手動権限付与</h4>
                  <label>
                    プラン
                    <select value={grantPlan} onChange={(event) => setGrantPlan(event.target.value)}>
                      <option value="standard">Standard</option>
                      <option value="pro">Pro</option>
                      <option value="promo_pro">Promo Pro</option>
                      <option value="launch_standard">Launch Standard</option>
                    </select>
                  </label>
                  <label>
                    有効日数（0で無期限）
                    <input type="number" min={0} max={3650} value={grantExpiresInDays} onChange={(event) => setGrantExpiresInDays(Number(event.target.value))} />
                  </label>
                  <button
                    className="small"
                    disabled={!highRiskReady}
                    onClick={() => window.confirm(`${grantPlan}を付与しますか？\n\n対象: ${selectedUser.email}\n有効日数: ${grantExpiresInDays === 0 ? "無期限" : `${grantExpiresInDays}日`}\nこの操作は監査ログに記録されます。`) && void manageUser("grant_entitlement", {
                      plan: grantPlan,
                      expiresInDays: grantExpiresInDays,
                    })}
                  >権限を付与</button>
                </div>
              </div>
            </section>}
            <h3>権限・プラン履歴</h3>
            <div className="tableWrap">
              <table className="compactTable">
                <thead><tr><th>プラン</th><th>状態</th><th>付与元</th><th>開始</th><th>期限</th></tr></thead>
                <tbody>
                  {selectedUser.entitlementGrants.map((grant) => (
                    <tr key={grant.id}>
                      <td>{grant.plan}</td>
                      <td>
                        {grant.status}
                        {hasCapability("users.manage") && grant.status === "active" && (
                          <button
                            className="small danger inlineAction"
                            disabled={!highRiskReady}
                            onClick={() => window.confirm(`${grant.plan}を取り消しますか？\n\n対象: ${selectedUser.email}\n権限ID: ${grant.id}\nこの操作は監査ログに記録されます。`) && void manageUser("revoke_entitlement", { grantId: grant.id })}
                          >取消</button>
                        )}
                      </td>
                      <td>{grant.source}{grant.storePlatform ? ` / ${grant.storePlatform}` : ""}</td>
                      <td>{formatDate(grant.startsAt)}</td>
                      <td>{formatDate(grant.expiresAt)}</td>
                    </tr>
                  ))}
                  {selectedUser.entitlementGrants.length === 0 && <tr><td colSpan={5} className="empty">権限履歴はありません。</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        ) : (
          <>
            <form className="userToolbar" onSubmit={submitUserDirectorySearch}>
              <label>
                メール・名前・UUID
                <input
                  value={directorySearch}
                  onChange={(event) => setDirectorySearch(event.target.value)}
                  placeholder="検索語を入力"
                  maxLength={200}
                />
              </label>
              <label>
                状態
                <select value={directoryStatus} onChange={(event) => setDirectoryStatus(event.target.value)}>
                  <option value="">すべて</option>
                  <option value="active">Active</option>
                  <option value="suspended">Suspended</option>
                  <option value="banned">Banned</option>
                </select>
              </label>
              <button type="submit" disabled={directoryLoading}>{directoryLoading ? "読み込み中..." : "検索"}</button>
              <button type="button" className="secondary" onClick={downloadVisibleUsersCsv} disabled={directoryUsers.length === 0}>表示中CSV</button>
            </form>
            <div className="tableWrap">
              <table>
                <thead><tr><th>メール</th><th>名前</th><th>作成日</th><th>最終利用</th><th>プラン</th><th>状態</th><th></th></tr></thead>
                <tbody>
                  {directoryUsers.map((user) => (
                    <tr key={user.id}>
                      <td>{user.email || "-"}</td>
                      <td>{user.displayName || "未設定"}</td>
                      <td className="compactDate" title={formatDate(user.createdAt)}>{formatCompactDate(user.createdAt)}</td>
                      <td className="compactDate" title={formatDate(user.lastSeenAt ?? user.lastSignInAt)}>
                        {formatCompactDate(user.lastSeenAt ?? user.lastSignInAt, true)}
                      </td>
                      <td><span className="chip">{user.currentPlan}</span></td>
                      <td><span className={`chip ${user.accountStatus}`}>{user.accountStatus}</span></td>
                      <td><button className="small secondary" onClick={() => void fetchUserDetail(user.id)}>詳細</button></td>
                    </tr>
                  ))}
                  {!directoryLoading && directoryUsers.length === 0 && <tr><td colSpan={7} className="empty">該当するユーザーはいません。</td></tr>}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <button
                className="small secondary"
                disabled={directoryLoading || directoryOffset === 0}
                onClick={() => void fetchUserDirectory(adminCapabilities, authHeaders, Math.max(0, directoryOffset - USER_PAGE_SIZE))}
              >前へ</button>
              <span>
                {directoryTotal === 0 ? 0 : Math.floor(directoryOffset / USER_PAGE_SIZE) + 1}
                {" / "}
                {Math.ceil(directoryTotal / USER_PAGE_SIZE)}ページ
              </span>
              <button
                className="small secondary"
                disabled={directoryLoading || directoryOffset + USER_PAGE_SIZE >= directoryTotal}
                onClick={() => void fetchUserDirectory(adminCapabilities, authHeaders, directoryOffset + USER_PAGE_SIZE)}
              >次へ</button>
            </div>
          </>
        )}
      </section>}

      {hasCapability("promos.issue") && <section className="grid">
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
                  userSearchController.cancel();
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
          <button data-testid="admin-submit" type="submit" disabled={isLoading || !highRiskReady}>{isLoading ? "送信中..." : "優待コードを送る"}</button>
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
      </section>}

      {hasCapability("promos.read") && <section className="panel">
        <div className="listHeader">
          <div>
            <h2>送信済みコード</h2>
            <p className="muted">Webhookで反映された送信済み、配達済み、拒否、迷惑報告、送信失敗を確認できます。</p>
          </div>
          <button className="secondary" onClick={() => void fetchCodes()}>再読み込み</button>
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
                    {hasCapability("promos.issue") && !row.revoked_at && !row.claimed_at && (
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
      </section>}

      <section className="grid operationsGrid">
        {hasCapability("support.read") && <section className="panel">
          <div className="listHeader">
            <div>
              <h2>サポートキュー</h2>
              <p className="muted">問い合わせ受付と配送状態を確認し、対応状態を記録します。</p>
            </div>
            <button className="secondary" onClick={() => void fetchOperations()}>再読み込み</button>
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
                      {hasCapability("support.write") ? (
                        <select
                          aria-label={`受付 ${row.request_id} の対応状態`}
                          value={row.support_status}
                          disabled={!highRiskReady}
                          onChange={(event) => void updateSupport(row, event.target.value as SupportRequestRow["support_status"])}
                        >
                          <option value="open">未対応</option>
                          <option value="in_progress">対応中</option>
                          <option value="resolved">解決</option>
                          <option value="closed">終了</option>
                        </select>
                      ) : statusText(row.support_status)}
                    </td>
                    <td>{formatDate(row.created_at)}</td>
                  </tr>
                ))}
                {supportRequests.length === 0 && <tr><td colSpan={5} className="empty">問い合わせはありません。</td></tr>}
              </tbody>
            </table>
          </div>
        </section>}

        {hasCapability("moderation.read") && <section className="panel">
          <div className="listHeader">
            <div>
              <h2>通報・モデレーション</h2>
              <p className="muted">共有コンテンツの通報を確認し、対応履歴を残します。</p>
            </div>
            <button className="secondary" onClick={() => void fetchOperations()}>再読み込み</button>
          </div>
          <div className="operationList">
            {reports.map((report) => (
              <article className="operationItem" key={report.id}>
                <div><strong>{report.category}</strong><span className="muted"> / {formatDate(report.created_at)}</span></div>
                <p>{report.details || "詳細なし"}</p>
                <div className="operationActions">
                  <span className="chip">{report.status}</span>
                  {hasCapability("moderation.manage") && <>
                    <button className="small secondary" disabled={!highRiskReady || report.status === "closed"} onClick={() => void moderateReport(report.id, "review")}>確認中</button>
                    <button className="small danger" disabled={!highRiskReady || report.status === "closed"} onClick={() => void moderateReport(report.id, "reject")}>却下</button>
                    <button className="small" disabled={!highRiskReady || report.status === "closed"} onClick={() => void moderateReport(report.id, "close")}>終了</button>
                  </>}
                </div>
              </article>
            ))}
            {reports.length === 0 && <p className="empty">未処理の通報はありません。</p>}
          </div>
        </section>}
      </section>

      {hasCapability("audit.read") && <section className="panel">
        <div className="listHeader">
          <div>
            <h2>管理監査ログ</h2>
            <p className="muted">優待コード、サポート、モデレーションの操作履歴です。</p>
          </div>
          <button className="secondary" onClick={() => void fetchOperations()}>再読み込み</button>
        </div>
        <div className="tableWrap">
          <table>
            <thead><tr><th>操作</th><th>operation ID</th><th>phase</th><th>assurance</th><th>管理者ID</th><th>対象ユーザーID</th><th>理由</th><th>日時</th></tr></thead>
            <tbody>
              {auditLogs.map((log) => (
                <tr key={log.id}>
                  <td>{log.action}</td>
                  <td><code>{log.operation_id ?? "-"}</code></td>
                  <td>{log.phase}</td>
                  <td>{log.assurance?.aal ?? "-"}{log.assurance?.methods?.length ? ` / ${log.assurance.methods.join(", ")}` : ""}</td>
                  <td><code>{log.admin_user_id}</code></td>
                  <td><code>{log.target_user_id ?? "-"}</code></td>
                  <td>{log.reason ?? "-"}</td>
                  <td>{formatDate(log.created_at)}</td>
                </tr>
              ))}
              {auditLogs.length === 0 && <tr><td colSpan={8} className="empty">監査ログはありません。</td></tr>}
            </tbody>
          </table>
        </div>
      </section>}
    </main>
  );
}
