import { NextRequest } from "next/server";
import { bootstrapEmails, normalizedEmail } from "./env";
import { createServiceSupabaseClient } from "./supabase";

export type AdminRole = "owner" | "billing" | "moderator" | "readonly";

export type AdminContext = {
  id: string;
  userId: string;
  email: string;
  role: AdminRole;
  canWrite: boolean;
};

function bearerToken(request: NextRequest): string | null {
  const header = request.headers.get("authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || null;
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
  const { data: existingAdmin, error: adminError } = await supabase
    .from("admin_users")
    .select("id,user_id,email,role,status")
    .eq("user_id", userId)
    .maybeSingle();

  if (adminError) {
    throw new Response(adminError.message, { status: 500 });
  }

  if (existingAdmin?.status === "suspended") {
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

  const role = admin.role as AdminRole;
  return {
    id: admin.id,
    userId,
    email,
    role,
    canWrite: role === "owner" || role === "billing",
  };
}

export function assertWritable(admin: AdminContext) {
  if (!admin.canWrite) {
    throw new Response("この管理者ロールでは変更できません", { status: 403 });
  }
}
