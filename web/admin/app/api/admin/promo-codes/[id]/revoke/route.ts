import { NextRequest, NextResponse } from "next/server";
import { assertHighRisk, assertWritable, requireAdmin } from "@/lib/auth";
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

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "優待コードを取り消せませんでした");
}

function rpcErrorMessage(error: unknown): string {
  if (error && typeof error === "object" && "message" in error && typeof error.message === "string") {
    return error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

export async function POST(
  request: NextRequest,
  context: { params: Promise<{ id: string }> },
) {
  try {
    const admin = await requireAdmin(request);
    assertWritable(admin);
    assertHighRisk(admin);
    const { id } = await context.params;
    const body = await request.json().catch(() => ({}));
    const reason = requiredAdminReason(body.reason);
    const operationId = requiredOperationId(body.operationId);
    const supabase = createServiceSupabaseClient();
    const auditAction = "promo_code_revoked";
    const operationName = "promo_code_revoke" as const;
    const existingOperation = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
    if (existingOperation) return duplicateAdminOperationResponse(existingOperation);
    const claim = await claimAdminOperation(supabase, admin.id, operationId, operationName);
    if (claim.existing) return duplicateClaimedOperationResponse(claim.existing);

    const { data, error } = await supabase.rpc("admin_revoke_promo_invite_code_audited", {
      p_code_id: id,
      p_admin_id: admin.id,
      p_actor_user_id: admin.userId,
      p_reason: reason,
      p_operation_id: operationId,
      p_assurance: admin.assurance,
      p_event_at: new Date().toISOString(),
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
      const message = rpcErrorMessage(error);
      if (message.includes("promo_code_already_claimed")) {
        return NextResponse.json({ error: "使用済みの優待コードは取り消せません" }, { status: 409 });
      }
      if (message.includes("promo_code_not_found") || message.includes("promo_code_already_revoked")) {
        return NextResponse.json({ error: "取消対象の優待コードが見つからないか、すでに取消済みです" }, { status: 404 });
      }
      throw error;
    }
    if (!data) {
      await markAdminOperation(supabase, admin.id, operationId, operationName, "failed", claim.persisted).catch(() => undefined);
      throw new Error("優待コード取消結果を確認できませんでした");
    }

    await markAdminOperation(supabase, admin.id, operationId, operationName, "completed", claim.persisted);
    return NextResponse.json({ ok: true }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
