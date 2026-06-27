import { NextRequest, NextResponse } from "next/server";
import { requireAdmin } from "@/lib/auth";
import { normalizedEmail } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "ユーザー検索に失敗しました";
  return NextResponse.json({ error: message }, { status: 500 });
}

export async function GET(request: NextRequest) {
  try {
    await requireAdmin(request);
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

    return NextResponse.json({ users });
  } catch (error) {
    return asErrorResponse(error);
  }
}
