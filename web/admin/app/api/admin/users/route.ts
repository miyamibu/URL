import { NextRequest, NextResponse } from "next/server";
import { assertCanReadUsers, assertCanSearchUsers, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";
import { normalizedEmail } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

type AdminUserDirectoryRpcRow = {
  user_id: string;
  email: string | null;
  display_name: string | null;
  created_at: string;
  last_sign_in_at: string | null;
  last_seen_at: string | null;
  current_plan: string | null;
  account_status: string | null;
  total_count: number | string;
};

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "ユーザー検索に失敗しました");
}

function boundedInteger(value: string | null, fallback: number, min: number, max: number): number {
  if (!value) return fallback;
  const parsed = Number(value);
  return Number.isInteger(parsed) ? Math.min(Math.max(parsed, min), max) : fallback;
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    const mode = request.nextUrl.searchParams.get("mode");
    if (mode === "directory") {
      assertCanReadUsers(admin);
      const query = (request.nextUrl.searchParams.get("q") ?? "").trim().slice(0, 200);
      const status = (request.nextUrl.searchParams.get("status") ?? "").trim().toLowerCase();
      if (status && !["active", "suspended", "banned"].includes(status)) {
        return NextResponse.json({ error: "ユーザー状態の指定が不正です" }, { status: 400 });
      }
      const limit = boundedInteger(request.nextUrl.searchParams.get("limit"), 50, 1, 200);
      const offset = boundedInteger(request.nextUrl.searchParams.get("offset"), 0, 0, 1_000_000);
      const supabase = createServiceSupabaseClient();
      const { data, error } = await supabase.rpc("admin_list_users", {
        p_search: query,
        p_status: status || null,
        p_limit: limit,
        p_offset: offset,
      });
      if (error) throw error;
      const rows = (data ?? []) as AdminUserDirectoryRpcRow[];
      return NextResponse.json(
        {
          users: rows.map((row) => ({
            id: row.user_id,
            email: row.email ?? "",
            displayName: row.display_name,
            createdAt: row.created_at,
            lastSignInAt: row.last_sign_in_at,
            lastSeenAt: row.last_seen_at,
            currentPlan: row.current_plan ?? "free",
            accountStatus: row.account_status ?? "active",
          })),
          total: Number(rows[0]?.total_count ?? 0),
          limit,
          offset,
        },
        { headers: { "Cache-Control": "no-store" } },
      );
    }

    assertCanSearchUsers(admin);
    const query = normalizedEmail(request.nextUrl.searchParams.get("q") ?? "");
    if (query.length < 2) {
      return NextResponse.json({ users: [] });
    }

    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase.auth.admin.listUsers({ page: 1, perPage: 200 });
    if (error) throw error;

    const users = (data.users ?? [])
      .filter((user) => normalizedEmail(user.email ?? "").includes(query))
      .slice(0, 20)
      .map((user) => ({
        id: user.id,
        email: normalizedEmail(user.email ?? ""),
        createdAt: user.created_at,
        lastSignInAt: user.last_sign_in_at,
      }));

    return NextResponse.json(
      { users },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return asErrorResponse(error);
  }
}
