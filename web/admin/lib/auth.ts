import { NextRequest } from "next/server";
import { bootstrapEmails, normalizedEmail } from "./env";
import { createServiceSupabaseClient } from "./supabase";

export type AdminRole = "owner" | "billing" | "moderator" | "readonly";
export type AdminCapability =
  | "billing.manage"
  | "promos.issue"
  | "support.read"
  | "support.write"
  | "moderation.manage"
  | "users.search"
  | "admins.manage"
  | "audit.read";

const ROLE_CAPABILITIES: Record<AdminRole, readonly AdminCapability[]> = {
  owner: [
    "billing.manage",
    "promos.issue",
    "support.read",
    "support.write",
    "moderation.manage",
    "users.search",
    "admins.manage",
    "audit.read",
  ],
  billing: ["billing.manage", "promos.issue", "users.search"],
  moderator: ["support.read", "support.write", "moderation.manage", "audit.read"],
  readonly: [],
};

export type AdminContext = {
  id: string;
  userId: string;
  email: string;
  role: AdminRole;
  capabilities: readonly AdminCapability[];
  assurance: AdminAssurance;
  canWrite: boolean;
};

export type AdminAssurance = {
  aal: "aal1" | "aal2";
  acr: string | null;
  methods: readonly string[];
  verifiedAt: number | null;
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

type SupabaseJwtAmr = {
  method?: unknown;
  timestamp?: unknown;
};

const MFA_AMR_METHODS = new Set([
  "totp",
]);
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

function verifiedAssurance(claims: SupabaseJwtClaims): AdminAssurance {
  const aal = claims.aal === "aal1" || claims.aal === "aal2" ? claims.aal : null;
  if (!aal) unauthorized();

  if (claims.acr !== undefined && claims.acr !== null && typeof claims.acr !== "string") {
    unauthorized();
  }
  const acr = typeof claims.acr === "string" && claims.acr.trim() ? claims.acr.trim() : null;

  if (claims.amr !== undefined && !Array.isArray(claims.amr)) unauthorized();
  const methods = new Set<string>();
  let verifiedAt: number | null = null;
  for (const entry of (claims.amr ?? []) as SupabaseJwtAmr[]) {
    if (!entry || typeof entry.method !== "string") unauthorized();
    const method = entry.method.trim().toLowerCase();
    if (!method) unauthorized();
    methods.add(method);
    if (MFA_AMR_METHODS.has(method)) {
      if (typeof entry.timestamp !== "number" || !Number.isFinite(entry.timestamp)) unauthorized();
      verifiedAt = Math.max(verifiedAt ?? 0, entry.timestamp);
    }
  }

  return {
    aal,
    acr,
    methods: [...methods],
    verifiedAt,
  };
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
  const assurance = verifiedAssurance(claims);

  const userId = userData.user.id;
  const email = normalizedEmail(userData.user.email);
  const { data: existingAdmin, error: adminError } = await supabase
    .from("admin_users")
    .select("id,user_id,email,role,status")
    .eq("user_id", userId)
    .maybeSingle();

  if (adminError) {
    throw new Response(adminError.message, { status: 500 });
  }

  if (existingAdmin && existingAdmin.status !== "active") {
    throw new Response("この管理者は停止されています", { status: 403 });
  }

  const bootstrap = bootstrapEmails();
  let admin = existingAdmin;
  if (!admin && bootstrap.has(email)) {
    const { data: inserted, error: insertError } = await supabase
      .from("admin_users")
      .insert({
        user_id: userId,
        email,
        role: "owner",
        status: "active",
      })
      .select("id,user_id,email,role,status")
      .single();

    if (insertError) {
      throw new Response(insertError.message, { status: 500 });
    }
    admin = inserted;
  }

  if (!admin) {
    throw new Response("管理者として許可されていません", { status: 403 });
  }

  if (!admin || !(admin.role in ROLE_CAPABILITIES)) {
    throw new Response("管理者ロールを確認できませんでした", { status: 403 });
  }
  const role = admin.role as AdminRole;
  const capabilities = ROLE_CAPABILITIES[role];
  return {
    id: admin.id,
    userId,
    email,
    role,
    capabilities,
    assurance,
    canWrite: capabilities.includes("billing.manage") || capabilities.includes("promos.issue"),
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

  const now = Math.floor(Date.now() / 1000);
  const age = now - admin.assurance.verifiedAt;
  if (age < -MAX_CLOCK_SKEW_SECONDS || age > stepUpMaxAgeSeconds()) {
    throw new Response("管理者の追加認証が期限切れです", {
      status: 428,
      headers: { "Cache-Control": "no-store" },
    });
  }

  if (admin.assurance.acr && /(?:^|[^a-z])(aal1|loa1|single[-_ ]factor)(?:$|[^a-z])/i.test(admin.assurance.acr)) {
    throw new Response("管理者の追加認証強度を確認できませんでした", {
      status: 428,
      headers: { "Cache-Control": "no-store" },
    });
  }
}

export function assertCanSearchUsers(admin: AdminContext) {
  assertCapability(admin, "users.search");
}

export function assertCanModerate(admin: AdminContext) {
  assertCapability(admin, "moderation.manage");
}
