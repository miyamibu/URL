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
import { createServiceSupabaseClient } from "@/lib/supabase";

const ACTIONS = new Set(["review", "warn", "hide_content", "suspend_user", "reject", "close"]);

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "モデレーションでエラーが発生しました");
}

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    assertCapability(admin, "moderation.read");
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
    assertCapability(admin, "moderation.manage");
    assertHighRisk(admin);
    const body = await request.json().catch(() => ({}));
    const reportId = String(body.reportId ?? "").trim();
    const action = String(body.action ?? "").trim();
    const reason = requiredAdminReason(body.reason);
    const operationId = requiredOperationId(body.operationId);
    if (!reportId || !ACTIONS.has(action)) {
      return NextResponse.json({ error: "通報IDとアクションを確認してください" }, { status: 400 });
    }
    const supabase = createServiceSupabaseClient();
    const auditAction = `moderation_${action}`;
    const operationName = "moderation_action" as const;
    const existingOperation = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
    if (existingOperation) return duplicateAdminOperationResponse(existingOperation);
    const claim = await claimAdminOperation(supabase, admin.id, operationId, operationName);
    if (claim.existing) return duplicateClaimedOperationResponse(claim.existing);
    const { data, error } = await supabase.rpc("admin_apply_moderation_action", {
      p_report_id: reportId,
      p_admin_id: admin.id,
      p_action: action,
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
    if (!data || typeof data !== "object" || !("report" in data)) {
      await markAdminOperation(supabase, admin.id, operationId, operationName, "failed", claim.persisted).catch(() => undefined);
      throw new Error("モデレーション結果を確認できませんでした");
    }
    await markAdminOperation(supabase, admin.id, operationId, operationName, "completed", claim.persisted);
    return NextResponse.json({ report: data.report }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
