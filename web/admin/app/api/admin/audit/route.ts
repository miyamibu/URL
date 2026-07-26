import { NextRequest, NextResponse } from "next/server";
import { assertCanModerate, requireAdmin } from "@/lib/auth";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "監査ログを取得できませんでした";
  return NextResponse.json({ error: message }, { status: 500 });
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanModerate(admin);
    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase
      .from("admin_audit_logs")
      .select("id,admin_user_id,target_user_id,action,reason,before_value,after_value,created_at")
      .order("created_at", { ascending: false })
      .limit(100);
    if (error) throw error;
    return NextResponse.json({ logs: data ?? [] }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
