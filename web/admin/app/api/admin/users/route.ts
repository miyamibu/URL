import { NextRequest, NextResponse } from "next/server";
import { assertCanSearchUsers, requireAdmin } from "@/lib/auth";
import { normalizedEmail } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  console.error("admin user search failed", error instanceof Error ? error.name : "unknown");
  return NextResponse.json({ error: "ユーザー検索に失敗しました" }, { status: 500 });
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanSearchUsers(admin);
    const query = normalizedEmail(request.nextUrl.searchParams.get("q") ?? "");
    if (query.length < 2) {
      return NextResponse.json({ users: [] });
    }

    const supabase = createServiceSupabaseClient();
    const matches = [];
    const maxPages = 100;
    for (let page = 1; page <= maxPages; page += 1) {
      const { data, error } = await supabase.auth.admin.listUsers({ page, perPage: 200 });
      if (error) throw error;
      for (const user of data.users ?? []) {
        const email = normalizedEmail(user.email ?? "");
        if (email.includes(query)) {
          matches.push({
            id: user.id,
            email,
            createdAt: user.created_at,
            lastSignInAt: user.last_sign_in_at,
          });
        }
      }
      if ((data.users ?? []).length < 200 || matches.length >= 20) break;
    }
    const users = matches.slice(0, 20);

    return NextResponse.json(
      { users },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return asErrorResponse(error);
  }
}
