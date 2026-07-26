import { NextRequest, NextResponse } from "next/server";
import { assertCanModerate, requireAdmin } from "@/lib/auth";
import { recordAdminAudit } from "@/lib/audit";
import { createServiceSupabaseClient } from "@/lib/supabase";

const ACTIONS = new Set(["review", "warn", "hide_content", "suspend_user", "reject", "close"]);
const STATUS_BY_ACTION: Record<string, string> = {
  review: "reviewing",
  reject: "rejected",
  close: "closed",
  warn: "actioned",
  hide_content: "actioned",
  suspend_user: "actioned",
};

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
    if (admin.role !== "owner" && admin.role !== "moderator") {
      throw new Response("この管理者ロールではモデレーションできません", { status: 403 });
    }
    const body = await request.json().catch(() => ({}));
    const reportId = String(body.reportId ?? "").trim();
    const action = String(body.action ?? "").trim();
    const reason = String(body.reason ?? "").trim().slice(0, 1000) || null;
    if (!reportId || !ACTIONS.has(action)) {
      return NextResponse.json({ error: "通報IDとアクションを確認してください" }, { status: 400 });
    }
    const supabase = createServiceSupabaseClient();
    const { data: before, error: beforeError } = await supabase
      .from("shared_content_reports")
      .select("id,reported_user_id,status,category")
      .eq("id", reportId)
      .maybeSingle();
    if (beforeError) throw beforeError;
    if (!before) return NextResponse.json({ error: "通報が見つかりません" }, { status: 404 });
    const nextStatus = STATUS_BY_ACTION[action];
    const { data: after, error: updateError } = await supabase
      .from("shared_content_reports")
      .update({ status: nextStatus })
      .eq("id", reportId)
      .select("id,reported_user_id,status,category,updated_at")
      .single();
    if (updateError) throw updateError;
    const { error: actionError } = await supabase.from("moderation_actions").insert({
      report_id: reportId,
      admin_user_id: admin.id,
      target_user_id: before.reported_user_id,
      action,
      reason,
    });
    if (actionError) throw actionError;
    if (action === "suspend_user" && before.reported_user_id) {
      const { error: suspendError } = await supabase
        .from("user_profiles")
        .update({ account_status: "suspended" })
        .eq("user_id", before.reported_user_id);
      if (suspendError) throw suspendError;
    }
    await recordAdminAudit({
      adminUserId: admin.id,
      targetUserId: before.reported_user_id,
      action: `moderation_${action}`,
      reason,
      beforeValue: before,
      afterValue: after,
    });
    return NextResponse.json({ report: after });
  } catch (error) {
    return asErrorResponse(error);
  }
}
