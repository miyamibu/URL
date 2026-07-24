import { NextRequest } from "next/server";
import { Buffer } from "node:buffer";
import { bootstrapEmails, normalizedEmail } from "./env";
import { createServiceSupabaseClient } from "./supabase";

export type AdminRole = "owner" | "billing" | "moderator" | "readonly";

export type AdminContext = {
  id: string;
  userId: string;
  email: string;
  role: AdminRole;
  canWrite: boolean;
  recentAuth: boolean;
};

function bearerToken(request: NextRequest): string | null {
  const header = request.headers.get("authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || null;
}

function hasAal2Totp(token: string): boolean {
  try {
    const payload = token.split(".")[1];
    if (!payload) return false;
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      aal?: unknown;
      amr?: Array<{ method?: unknown }>;
    };
    return claims.aal === "aal2" && Array.isArray(claims.amr) &&
      claims.amr.some((entry) => entry?.method === "totp");
  } catch {
    return false;
  }
}

function hasRecentAuth(token: string, maxAgeSeconds = 15 * 60): boolean {
  try {
    const payload = token.split(".")[1];
    if (!payload) return false;
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as { auth_time?: unknown };
    const authTime = typeof claims.auth_time === "number" ? claims.auth_time : Number(claims.auth_time);
    const ageSeconds = Math.floor(Date.now() / 1000) - authTime;
    return Number.isFinite(authTime) && authTime > 0 && ageSeconds >= 0 && ageSeconds <= maxAgeSeconds;
  } catch {
    return false;
  }
}

export async function requireAdmin(request: NextRequest): Promise<AdminContext> {
  const token = bearerToken(request);
  if (!token) {
    throw new Response("認証が必要です", { status: 401 });
  }

  const supabase = createServiceSupabaseClient();
  const { data: userData, error: userError } = await supabase.auth.getUser(token);
  if (userError || !userData.user?.email) {
    throw new Response("認証を確認できませんでした", { status: 401 });
  }

  const userId = userData.user.id;
  const email = normalizedEmail(userData.user.email);
  if (process.env.URLSAVER_ADMIN_MFA_REQUIRED !== "true") {
    throw new Response("管理者MFAが設定されていません", { status: 503 });
  }
  if (!hasAal2Totp(token)) {
    throw new Response("管理者MFAが必要です", { status: 403 });
  }
  const { data: existingAdmin, error: adminError } = await supabase
    .from("admin_users")
    .select("id,user_id,email,role,status")
    .eq("user_id", userId)
    .maybeSingle();

  if (adminError) {
    console.error("admin lookup failed", adminError.code ?? "unknown");
    throw new Response("管理者情報を確認できませんでした", { status: 500 });
  }

  if (existingAdmin?.status === "suspended") {
    throw new Response("この管理者は停止されています", { status: 403 });
  }

  const bootstrap = bootstrapEmails();
  let admin = existingAdmin;
  if (!admin && bootstrap.has(email)) {
    const { data: insertedRows, error: insertError } = await supabase.rpc("bootstrap_first_admin", {
      p_user_id: userId,
      p_email: email,
    });

    if (insertError) {
      console.error("admin bootstrap failed", insertError.code ?? "unknown");
      throw new Response("管理者bootstrapを確認できませんでした", { status: 503 });
    }
    admin = Array.isArray(insertedRows) ? insertedRows[0] ?? null : insertedRows;
  }

  if (!admin) {
    throw new Response("管理者として許可されていません", { status: 403 });
  }

  const role = admin.role as AdminRole;
  return {
    id: admin.id,
    userId,
    email,
    role,
    canWrite: role === "owner" || role === "billing",
    recentAuth: hasRecentAuth(token),
  };
}

export function assertWritable(admin: AdminContext) {
  if (!admin.canWrite) {
    throw new Response("この管理者ロールでは変更できません", { status: 403 });
  }
}

export function assertRecentAuth(admin: AdminContext) {
  if (!admin.recentAuth) {
    throw new Response("重要な操作には再認証が必要です", { status: 403 });
  }
}

export function assertCanSearchUsers(admin: AdminContext) {
  if (admin.role !== "owner" && admin.role !== "billing") {
    throw new Response("この管理者ロールではユーザー検索できません", { status: 403 });
  }
}
