import { NextRequest, NextResponse } from "next/server";
import { assertCanModerate, requireAdmin } from "@/lib/auth";
import { recordAdminAudit } from "@/lib/audit";
import { createServiceSupabaseClient } from "@/lib/supabase";

const STATUSES = new Set(["open", "in_progress", "resolved", "closed"]);

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "サポートキューでエラーが発生しました";
  return NextResponse.json({ error: message }, { status: 500 });
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanModerate(admin);
    const status = request.nextUrl.searchParams.get("status");
    const supabase = createServiceSupabaseClient();
    let query = supabase
      .from("contact_support_requests")
      .select("id,request_id,platform,app_version,build_type,is_signed_in,delivery_status,delivery_provider,delivery_event_type,delivery_event_at,delivery_error,support_status,assigned_admin_id,admin_note,created_at,updated_at")
      .order("created_at", { ascending: false })
      .limit(100);
    if (status && STATUSES.has(status)) query = query.eq("support_status", status);
    const { data, error } = await query;
    if (error) throw error;
    return NextResponse.json({ requests: data ?? [] }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCanModerate(admin);
    const body = await request.json().catch(() => ({}));
    const id = String(body.id ?? "").trim();
    const supportStatus = String(body.supportStatus ?? "").trim();
    if (!id || !STATUSES.has(supportStatus)) {
      return NextResponse.json({ error: "サポート状態を確認してください" }, { status: 400 });
    }
    const supabase = createServiceSupabaseClient();
    const { data: before, error: beforeError } = await supabase
      .from("contact_support_requests")
      .select("id,support_status,assigned_admin_id,admin_note")
      .eq("id", id)
      .maybeSingle();
    if (beforeError) throw beforeError;
    if (!before) return NextResponse.json({ error: "問い合わせが見つかりません" }, { status: 404 });
    const adminNote = typeof body.adminNote === "string" ? body.adminNote.trim().slice(0, 2000) || null : before.admin_note;
    const { data: after, error } = await supabase
      .from("contact_support_requests")
      .update({ support_status: supportStatus, assigned_admin_id: admin.id, admin_note: adminNote })
      .eq("id", id)
      .select("id,support_status,assigned_admin_id,admin_note,updated_at")
      .single();
    if (error) throw error;
    await recordAdminAudit({
      adminUserId: admin.id,
      action: "support_request_status_updated",
      reason: adminNote,
      beforeValue: before,
      afterValue: after,
    });
    return NextResponse.json({ request: after });
  } catch (error) {
    return asErrorResponse(error);
  }
}
