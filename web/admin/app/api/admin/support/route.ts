import { NextRequest, NextResponse } from "next/server";
import { assertCapability, assertHighRisk, requireAdmin } from "@/lib/auth";
import { requiredAdminReason } from "@/lib/audit";
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
    assertCapability(admin, "support.read");
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
    assertCapability(admin, "support.write");
    assertHighRisk(admin);
    const body = await request.json().catch(() => ({}));
    const id = String(body.id ?? "").trim();
    const supportStatus = String(body.supportStatus ?? "").trim();
    const reason = requiredAdminReason(body.reason);
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
    const { data, error } = await supabase.rpc("admin_update_support_request", {
      p_request_id: id,
      p_admin_id: admin.id,
      p_support_status: supportStatus,
      p_admin_note: adminNote,
      p_reason: reason,
      p_operation_id: crypto.randomUUID(),
      p_assurance: admin.assurance,
    });
    if (error) throw error;
    if (!data || typeof data !== "object" || !("request" in data)) {
      throw new Error("サポート更新結果を確認できませんでした");
    }
    return NextResponse.json({ request: data.request });
  } catch (error) {
    return asErrorResponse(error);
  }
}
