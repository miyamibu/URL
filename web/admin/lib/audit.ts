import { createServiceSupabaseClient } from "./supabase";

type AuditInput = {
  adminUserId: string;
  action: string;
  targetUserId?: string | null;
  reason?: string | null;
  beforeValue?: Record<string, unknown> | null;
  afterValue?: Record<string, unknown> | null;
};

function safeObject(value?: Record<string, unknown> | null): Record<string, unknown> | null {
  if (!value) return null;
  return Object.fromEntries(
    Object.entries(value).filter(([key]) => !/(token|secret|password|message|body|email)/i.test(key)),
  );
}

export async function recordAdminAudit(input: AuditInput): Promise<void> {
  const supabase = createServiceSupabaseClient();
  const { error } = await supabase.from("admin_audit_logs").insert({
    admin_user_id: input.adminUserId,
    target_user_id: input.targetUserId ?? null,
    action: input.action,
    reason: input.reason ?? null,
    before_value: safeObject(input.beforeValue),
    after_value: safeObject(input.afterValue),
  });
  if (error) throw error;
}
