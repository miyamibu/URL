import { NextRequest } from "next/server";
import { bootstrapEmails, normalizedEmail } from "./env";
import { createServiceSupabaseClient } from "./supabase";
import {
  capabilitiesForRole,
  hasWeakAssuranceContext,
  isStepUpCurrent,
  parseAdminAssurance,
  stepUpExpiresAt,
  type AdminAssurance,
  type AdminCapability,
  type AdminRole,
} from "./auth-policy";

export type { AdminAssurance, AdminCapability, AdminRole } from "./auth-policy";

export type AdminContext = {
  id: string;
  userId: string;
  email: string;
  role: AdminRole;
  capabilities: readonly AdminCapability[];
  assurance: AdminAssurance;
  canWrite: boolean;
};

type SupabaseJwtClaims = {
  iss?: unknown;
  aud?: unknown;
  sub?: unknown;
  role?: unknown;
  email?: unknown;
  is_anonymous?: unknown;
  aal?: unknown;
  acr?: unknown;
  amr?: unknown;
};

const DEFAULT_STEP_UP_MAX_AGE_SECONDS = 15 * 60;
const MAX_CLOCK_SKEW_SECONDS = 60;

function bearerToken(request: NextRequest): string | null {
  const header = request.headers.get("authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || null;
}

function unauthorized(): never {
  throw new Response("認証を確認できませんでした", { status: 401 });
}

function isAudienceAuthenticated(value: unknown): boolean {
  return value === "authenticated" || (Array.isArray(value) && value.includes("authenticated"));
}

function issuerMatchesSupabaseProject(value: unknown): boolean {
  if (typeof value !== "string") return false;
  try {
    const configured = new URL(process.env.NEXT_PUBLIC_SUPABASE_URL ?? "");
    const issuer = new URL(value);
    const configuredPath = configured.pathname.replace(/\/$/, "");
    return (
      issuer.origin === configured.origin &&
      issuer.pathname.replace(/\/$/, "") === `${configuredPath}/auth/v1`
    );
  } catch {
    return false;
  }
}

function stepUpMaxAgeSeconds(): number {
  const raw = process.env.URLSAVER_ADMIN_STEP_UP_MAX_AGE_SECONDS?.trim();
  if (!raw) return DEFAULT_STEP_UP_MAX_AGE_SECONDS;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < 60 || value > 24 * 60 * 60) {
    throw new Response("管理者step-up設定が不正です", { status: 500 });
  }
  return value;
}

export async function requireAdmin(request: NextRequest): Promise<AdminContext> {
  const token = bearerToken(request);
  if (!token) {
    throw new Response("認証が必要です", { status: 401 });
  }

  const supabase = createServiceSupabaseClient();
  let claims: SupabaseJwtClaims;
  try {
    const { data: claimsData, error: claimsError } = await supabase.auth.getClaims(token);
    if (claimsError || !claimsData?.claims) unauthorized();
    claims = claimsData.claims as SupabaseJwtClaims;
  } catch (error) {
    if (error instanceof Response) throw error;
    unauthorized();
  }

  if (
    !issuerMatchesSupabaseProject(claims.iss) ||
    !isAudienceAuthenticated(claims.aud) ||
    claims.role !== "authenticated" ||
    typeof claims.sub !== "string" ||
    (claims.is_anonymous !== undefined && claims.is_anonymous !== false)
  ) {
    unauthorized();
  }

  let userData: Awaited<ReturnType<typeof supabase.auth.getUser>>["data"];
  try {
    userData = (await supabase.auth.getUser(token)).data;
  } catch {
    unauthorized();
  }
  if (!userData.user?.email || userData.user.id !== claims.sub) unauthorized();
  if (typeof claims.email === "string" && normalizedEmail(claims.email) !== normalizedEmail(userData.user.email)) {
    unauthorized();
  }
  const assurance = parseAdminAssurance(claims);
  if (!assurance) unauthorized();

  const userId = userData.user.id;
  const email = normalizedEmail(userData.user.email);
  const { data: existingAdmin, error: adminError } = await supabase
    .from("admin_users")
    .select("id,user_id,email,role,status")
    .eq("user_id", userId)
    .maybeSingle();

  if (adminError) {
    throw new Response("管理者情報を取得できませんでした", { status: 500 });
  }

  if (existingAdmin && existingAdmin.status !== "active") {
    throw new Response("この管理者は停止されています", { status: 403 });
  }

  const bootstrap = bootstrapEmails();
  let admin = existingAdmin;
  if (!admin && bootstrap.has(email)) {
    const { data: bootstrapped, error: bootstrapError } = await supabase.rpc("bootstrap_first_admin", {
      p_user_id: userId,
      p_email: email,
    });
    if (bootstrapError) {
      throw new Response("管理者の初期登録を完了できませんでした", { status: 500 });
    }
    const inserted = Array.isArray(bootstrapped) ? bootstrapped[0] : bootstrapped;
    if (!inserted || typeof inserted !== "object") {
      throw new Response("管理者の初期登録を完了できませんでした", { status: 500 });
    }
    admin = inserted as typeof admin;
  }

  if (!admin) {
    throw new Response("管理者として許可されていません", { status: 403 });
  }

  const capabilities = capabilitiesForRole(admin.role);
  if (!capabilities) {
    throw new Response("管理者ロールを確認できませんでした", { status: 403 });
  }
  const role = admin.role as AdminRole;
  return {
    id: admin.id,
    userId,
    email,
    role,
    capabilities,
    assurance,
    canWrite: capabilities.includes("promos.issue"),
  };
}

export function assertCapability(admin: AdminContext, capability: AdminCapability) {
  if (!admin.capabilities.includes(capability)) {
    throw new Response("この管理者ロールでは許可されていない操作です", { status: 403 });
  }
}

export function assertWritable(admin: AdminContext) {
  assertCapability(admin, "promos.issue");
}

export function assertHighRisk(admin: AdminContext) {
  if (admin.assurance.aal !== "aal2" || admin.assurance.verifiedAt === null) {
    throw new Response("管理者の追加認証（AAL2/TOTP）が必要です", {
      status: 428,
      headers: { "Cache-Control": "no-store" },
    });
  }

  if (hasWeakAssuranceContext(admin.assurance.acr)) {
    throw new Response("管理者の追加認証強度を確認できませんでした", {
      status: 428,
      headers: { "Cache-Control": "no-store" },
    });
  }

  if (!isStepUpCurrent(
    admin.assurance,
    Math.floor(Date.now() / 1000),
    stepUpMaxAgeSeconds(),
    MAX_CLOCK_SKEW_SECONDS,
  )) {
    throw new Response("管理者の追加認証が期限切れです", {
      status: 428,
      headers: { "Cache-Control": "no-store" },
    });
  }
}

export function adminStepUpExpiresAt(admin: AdminContext): number | null {
  return stepUpExpiresAt(admin.assurance, stepUpMaxAgeSeconds());
}

export function assertCanSearchUsers(admin: AdminContext) {
  assertCapability(admin, "users.search");
}

export function assertCanReadUsers(admin: AdminContext) {
  assertCapability(admin, "users.read");
}

export function assertCanManageUsers(admin: AdminContext) {
  assertCapability(admin, "users.manage");
}

export function assertCanModerate(admin: AdminContext) {
  assertCapability(admin, "moderation.manage");
}
