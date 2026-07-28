import { NextRequest, NextResponse } from "next/server";
import { assertCapability, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "監査ログを取得できませんでした");
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCapability(admin, "audit.read");
    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase
      .from("admin_audit_logs")
      .select("id,admin_user_id,target_user_id,action,reason,before_value,after_value,operation_id,phase,assurance,created_at")
      .order("created_at", { ascending: false })
      .limit(100);
    if (error) throw error;
    return NextResponse.json({ logs: data ?? [] }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
