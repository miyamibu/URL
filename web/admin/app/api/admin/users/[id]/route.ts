import { NextRequest, NextResponse } from "next/server";
import { assertCanManageUsers, assertCanReadUsers, assertHighRisk, requireAdmin } from "@/lib/auth";
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

type AdminUserDetailRpcRow = {
  user_id: string;
  email: string | null;
  email_confirmed_at: string | null;
  display_name: string | null;
  auth_provider: string | null;
  created_at: string;
  last_sign_in_at: string | null;
  last_seen_at: string | null;
  account_status: string | null;
  admin_note: string | null;
  support_ticket_id: string | null;
  entitlement_grants: unknown;
};

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "ユーザー詳細を取得できませんでした");
}

const AUDIT_ACTIONS: Record<string, string> = {
  update_note: "user_admin_note_updated",
  set_status: "user_status_updated",
  grant_entitlement: "user_entitlement_granted",
  revoke_entitlement: "user_entitlement_revoked",
};

function rpcErrorMessage(error: unknown): string {
  if (error && typeof error === "object" && "message" in error && typeof error.message === "string") {
    return error.message;
  }
  return error instanceof Error ? error.message : String(error);
}

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ id: string }> },
) {
  try {
    const admin = await requireAdmin(request);
    assertCanReadUsers(admin);
    const { id } = await context.params;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
      return NextResponse.json({ error: "ユーザーIDが不正です" }, { status: 400 });
    }

    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase.rpc("admin_get_user", { p_user_id: id }).maybeSingle();
    if (error) throw error;
    if (!data) {
      return NextResponse.json({ error: "ユーザーが見つかりません" }, { status: 404 });
    }
    const user = data as AdminUserDetailRpcRow;

    return NextResponse.json(
      {
        user: {
          id: user.user_id,
          email: user.email ?? "",
          emailConfirmedAt: user.email_confirmed_at,
          displayName: user.display_name,
          authProvider: user.auth_provider,
          createdAt: user.created_at,
          lastSignInAt: user.last_sign_in_at,
          lastSeenAt: user.last_seen_at,
          accountStatus: user.account_status ?? "active",
          adminNote: user.admin_note,
          supportTicketId: user.support_ticket_id,
          entitlementGrants: Array.isArray(user.entitlement_grants) ? user.entitlement_grants : [],
        },
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return asErrorResponse(error);
  }
}

export async function PATCH(
  request: NextRequest,
  context: { params: Promise<{ id: string }> },
) {
  try {
    const admin = await requireAdmin(request);
    assertCanManageUsers(admin);
    assertHighRisk(admin);
    const { id } = await context.params;
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
      return NextResponse.json({ error: "ユーザーIDが不正です" }, { status: 400 });
    }
    const body = await request.json().catch(() => ({}));
    const action = typeof body.action === "string" ? body.action.trim().toLowerCase() : "";
    const auditAction = AUDIT_ACTIONS[action];
    if (!auditAction) {
      return NextResponse.json({ error: "管理操作が不正です" }, { status: 400 });
    }
    const reason = requiredAdminReason(body.reason);
    const operationId = requiredOperationId(body.operationId);
    const expiresInDays = Number(body.expiresInDays);
    if (
      action === "grant_entitlement"
      && (!Number.isInteger(expiresInDays) || expiresInDays < 0 || expiresInDays > 3650)
    ) {
      return NextResponse.json({ error: "有効日数は0〜3650日の整数で指定してください" }, { status: 400 });
    }
    const expiresAt = action === "grant_entitlement" && Number.isInteger(expiresInDays) && expiresInDays > 0
      ? new Date(Date.now() + expiresInDays * 86_400_000).toISOString()
      : null;
    const supabase = createServiceSupabaseClient();
    const existingOperation = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
    if (existingOperation) return duplicateAdminOperationResponse(existingOperation);
    const operationName = "user_manage" as const;
    const claim = await claimAdminOperation(supabase, admin.id, operationId, operationName);
    if (claim.existing) return duplicateClaimedOperationResponse(claim.existing);

    const { data, error } = await supabase.rpc("admin_manage_user_audited", {
      p_target_user_id: id,
      p_admin_id: admin.id,
      p_actor_user_id: admin.userId,
      p_action: action,
      p_reason: reason,
      p_operation_id: operationId,
      p_assurance: admin.assurance,
      p_account_status: typeof body.accountStatus === "string" ? body.accountStatus : null,
      p_admin_note: typeof body.adminNote === "string" ? body.adminNote : null,
      p_plan: typeof body.plan === "string" ? body.plan : null,
      p_expires_at: expiresAt,
      p_grant_id: typeof body.grantId === "string" ? body.grantId : null,
    });
    if (error) {
      await markAdminOperation(supabase, admin.id, operationId, operationName, "failed", claim.persisted).catch(() => undefined);
      if (isUniqueViolation(error)) {
        const duplicate = await findExistingAdminOperation(supabase, admin.id, operationId, auditAction);
        if (duplicate) return duplicateAdminOperationResponse(duplicate);
      }
      const message = rpcErrorMessage(error);
      if (message.includes("admin_self_lockout_denied")) {
        return NextResponse.json({ error: "自分自身の管理者アカウントは停止・BANできません" }, { status: 409 });
      }
      if (message.includes("not_found")) {
        return NextResponse.json({ error: "対象ユーザーまたは権限が見つかりません" }, { status: 404 });
      }
      if (message.includes("invalid") || message.includes("required") || message.includes("not_active")) {
        return NextResponse.json({ error: "管理操作の入力値または現在状態が不正です" }, { status: 400 });
      }
      throw error;
    }
    await markAdminOperation(supabase, admin.id, operationId, operationName, "completed", claim.persisted);
    return NextResponse.json({ ok: true, result: data }, { headers: { "Cache-Control": "no-store" } });
  } catch (error) {
    return asErrorResponse(error);
  }
}
