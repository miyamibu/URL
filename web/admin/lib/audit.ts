import { createServiceSupabaseClient } from "./supabase";
import type { AdminAssurance } from "./auth";

type AuditInput = {
  adminUserId: string;
  action: string;
  reason: string;
  targetUserId?: string | null;
  beforeValue?: Record<string, unknown> | null;
  afterValue?: Record<string, unknown> | null;
  operationId?: string | null;
  phase?: "started" | "completed" | "failed";
  assurance?: AdminAssurance | null;
};

function safeObject(value?: Record<string, unknown> | null): Record<string, unknown> | null {
  if (!value) return null;
  return Object.fromEntries(
    Object.entries(value).filter(([key]) => !/(token|secret|password|message|body|email)/i.test(key)),
  );
}

export function requiredAdminReason(value: unknown): string {
  const reason = typeof value === "string" ? value.trim().slice(0, 1000) : "";
  if (!reason) {
    throw new Response("管理操作の理由は必須です", { status: 400 });
  }
  return reason;
}

export async function recordAdminAudit(input: AuditInput): Promise<void> {
  const supabase = createServiceSupabaseClient();
  const reason = requiredAdminReason(input.reason);
  const { data, error } = await supabase.rpc("record_admin_audit", {
    p_admin_user_id: input.adminUserId,
    p_target_user_id: input.targetUserId ?? null,
    p_action: input.action,
    p_reason: reason,
    p_before_value: safeObject(input.beforeValue),
    p_after_value: safeObject(input.afterValue),
    p_operation_id: input.operationId ?? null,
    p_phase: input.phase ?? "completed",
    p_assurance: input.assurance ?? {},
  });
  if (error) throw error;
  if (typeof data !== "string" || !data) throw new Error("監査記録IDを確認できませんでした");
}
