import { NextRequest, NextResponse } from "next/server";
import { assertCapability, assertHighRisk, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";
import {
  claimAdminOperation,
  duplicateAdminOperationResponse,
  duplicateClaimedOperationResponse,
  findExistingAdminOperation,
  isUniqueViolation,
  markAdminOperation,
  requiredOperationId,
} from "@/lib/admin-operation";
import { requiredAdminReason } from "@/lib/audit";
import { parseJsonObject } from "@/lib/request-body";
import { createServiceSupabaseClient } from "@/lib/supabase";

const STATUSES = new Set(["open", "in_progress", "resolved", "closed"]);

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "サポートキューでエラーが発生しました");
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
    const body = await parseJsonObject(request);
    const id = String(body.id ?? "").trim();
    const supportStatus = String(body.supportStatus ?? "").trim();
    const reason = requiredAdminReason(body.reason);
    const operationId = requiredOperationId(body.operationId);
    if (!id || !STATUSES.has(supportStatus)) {
      return NextResponse.json({ error: "サポート状態を確認してください" }, { status: 400 });
    }
    const supabase = createServiceSupabaseClient();
    const auditAction = "support_request_status_updated";
    const operationName = "support_update" as const;
    const existingOperation = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
    if (existingOperation) return duplicateAdminOperationResponse(existingOperation);
    const { data: before, error: beforeError } = await supabase
      .from("contact_support_requests")
      .select("id,support_status,assigned_admin_id,admin_note")
      .eq("id", id)
      .maybeSingle();
    if (beforeError) throw beforeError;
    if (!before) return NextResponse.json({ error: "問い合わせが見つかりません" }, { status: 404 });
    const claim = await claimAdminOperation(supabase, admin.id, operationId, operationName);
    if (claim.existing) return duplicateClaimedOperationResponse(claim.existing);
    const adminNote = typeof body.adminNote === "string" ? body.adminNote.trim().slice(0, 2000) || null : before.admin_note;
    const { data, error } = await supabase.rpc("admin_update_support_request", {
      p_request_id: id,
      p_admin_id: admin.id,
      p_support_status: supportStatus,
      p_admin_note: adminNote,
      p_reason: reason,
      p_operation_id: operationId,
      p_assurance: admin.assurance,
    });
    if (error) {
      await markAdminOperation(supabase, admin.id, operationId, operationName, "failed", claim.persisted).catch(() => undefined);
      if (isUniqueViolation(error)) {
        const duplicate = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
        if (duplicate) {
          await markAdminOperation(supabase, admin.id, operationId, operationName, "completed", claim.persisted).catch(() => undefined);
          return duplicateAdminOperationResponse(duplicate);
        }
      }
      throw error;
    }
    if (!data || typeof data !== "object" || !("request" in data)) {
      await markAdminOperation(supabase, admin.id, operationId, operationName, "failed", claim.persisted).catch(() => undefined);
      throw new Error("サポート更新結果を確認できませんでした");
    }
    await markAdminOperation(supabase, admin.id, operationId, operationName, "completed", claim.persisted);
    return NextResponse.json({ request: data.request }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
