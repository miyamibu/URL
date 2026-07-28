import { NextRequest, NextResponse } from "next/server";
import { assertCanModerate, assertHighRisk, requireAdmin } from "@/lib/auth";
import { requiredAdminReason } from "@/lib/audit";
import { createServiceSupabaseClient } from "@/lib/supabase";

const ACTIONS = new Set(["review", "warn", "hide_content", "suspend_user", "reject", "close"]);

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "モデレーションでエラーが発生しました";
  return NextResponse.json({ error: message }, { status: 500 });
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanModerate(admin);
    const status = request.nextUrl.searchParams.get("status");
    const supabase = createServiceSupabaseClient();
    let query = supabase
      .from("shared_content_reports")
      .select("id,reporter_user_id,reported_user_id,shared_tag_id,shared_tag_group_id,shared_url_id,category,details,status,created_at,updated_at")
      .order("created_at", { ascending: false })
      .limit(100);
    if (status) query = query.eq("status", status);
    const { data, error } = await query;
    if (error) throw error;
    return NextResponse.json({ reports: data ?? [] }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanModerate(admin);
    assertHighRisk(admin);
    const body = await request.json().catch(() => ({}));
    const reportId = String(body.reportId ?? "").trim();
    const action = String(body.action ?? "").trim();
    const reason = requiredAdminReason(body.reason);
    if (!reportId || !ACTIONS.has(action)) {
      return NextResponse.json({ error: "通報IDとアクションを確認してください" }, { status: 400 });
    }
    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase.rpc("admin_apply_moderation_action", {
      p_report_id: reportId,
      p_admin_id: admin.id,
      p_action: action,
      p_reason: reason,
      p_operation_id: crypto.randomUUID(),
      p_assurance: admin.assurance,
    });
    if (error) throw error;
    if (!data || typeof data !== "object" || !("report" in data)) {
      throw new Error("モデレーション結果を確認できませんでした");
    }
    return NextResponse.json({ report: data.report });
  } catch (error) {
    return asErrorResponse(error);
  }
}
