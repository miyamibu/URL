import type { createServiceSupabaseClient } from "./supabase";
import { NextResponse } from "next/server";

type ServiceSupabase = ReturnType<typeof createServiceSupabaseClient>;

export type AdminOperationName = "support_update" | "moderation_action" | "promo_code_revoke";

export type ExistingClaimedOperation = {
  operationId: string;
  operation: AdminOperationName;
  status: "started" | "completed" | "failed";
  codeId: string | null;
  createdAt: string;
  updatedAt: string;
};

export type AdminOperationClaim = {
  persisted: boolean;
  existing: ExistingClaimedOperation | null;
};

export function parseOperationId(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(normalized)
    ? normalized
    : null;
}

async function getClaimedOperation(
  supabase: ServiceSupabase,
  adminUserId: string,
  operationId: string,
  operation: AdminOperationName,
): Promise<ExistingClaimedOperation | null> {
  const { data, error } = await supabase
    .from("admin_operation_idempotency")
    .select("operation_id,operation,status,code_id,created_at,updated_at")
    .eq("admin_user_id", adminUserId)
    .eq("operation_id", operationId)
    .eq("operation", operation)
    .maybeSingle();
  if (error) throw error;
  if (!data) return null;
  return {
    operationId: data.operation_id,
    operation: data.operation as AdminOperationName,
    status: data.status as ExistingClaimedOperation["status"],
    codeId: data.code_id,
    createdAt: data.created_at,
    updatedAt: data.updated_at,
  };
}

function isPreNamespaceConstraint(error: unknown): boolean {
  if (!error || typeof error !== "object" || !("code" in error) || error.code !== "23514") return false;
  const summary = ["message", "details", "hint"]
    .map((key) => key in error && typeof error[key as keyof typeof error] === "string" ? error[key as keyof typeof error] : "")
    .join(" ");
  return summary.includes("admin_operation_idempotency_operation_check");
}

export async function claimAdminOperation(
  supabase: ServiceSupabase,
  adminUserId: string,
  operationId: string,
  operation: AdminOperationName,
): Promise<AdminOperationClaim> {
  const { error } = await supabase.from("admin_operation_idempotency").insert({
    admin_user_id: adminUserId,
    operation_id: operationId,
    operation,
  });
  if (!error) return { persisted: true, existing: null };
  if (error.code === "23505") {
    const existing = await getClaimedOperation(supabase, adminUserId, operationId, operation);
    if (!existing) throw new Error("admin operation claim state unavailable");
    return {
      persisted: true,
      existing,
    };
  }
  // During the fixed-Web-first cutover, the frozen 20260727150000 check only
  // knows promo_code_issue. The audit unique index still makes these RPCs
  // idempotent until the additive namespace migration is applied.
  if (isPreNamespaceConstraint(error)) return { persisted: false, existing: null };
  throw error;
}

export async function markAdminOperation(
  supabase: ServiceSupabase,
  adminUserId: string,
  operationId: string,
  operation: AdminOperationName,
  status: "completed" | "failed",
  persisted: boolean,
): Promise<void> {
  if (!persisted) return;
  const { data, error } = await supabase
    .from("admin_operation_idempotency")
    .update({ status })
    .eq("admin_user_id", adminUserId)
    .eq("operation_id", operationId)
    .eq("operation", operation)
    .select("operation_id")
    .single();
  if (error) throw error;
  if (!data) throw new Error("admin operation state was not updated");
}

export function duplicateClaimedOperationResponse(operation: ExistingClaimedOperation | null): Response {
  return NextResponse.json(
    {
      error: "duplicate_operation",
      operation: operation ?? { status: "unknown" },
    },
    { status: 409, headers: { "Cache-Control": "no-store" } },
  );
}

export function requiredOperationId(value: unknown): string {
  const operationId = parseOperationId(value);
  if (!operationId) {
    throw new Response("operation_id_required", { status: 400 });
  }
  return operationId;
}

export type ExistingAdminOperation = {
  id: string;
  admin_user_id: string;
  operation_id: string;
  action: string;
  phase: string;
  assurance: Record<string, unknown> | null;
  created_at: string;
};

export async function findExistingAdminOperation(
  supabase: ServiceSupabase,
  adminUserId: string,
  operationId: string,
  action: string,
): Promise<ExistingAdminOperation | null> {
  const { data, error } = await supabase
    .from("admin_audit_logs")
    .select("id,admin_user_id,operation_id,action,phase,assurance,created_at")
    .eq("admin_user_id", adminUserId)
    .eq("operation_id", operationId)
    .eq("action", action)
    .maybeSingle();
  if (error) throw error;
  return (data as ExistingAdminOperation | null) ?? null;
}

export function duplicateAdminOperationResponse(operation: ExistingAdminOperation): Response {
  return NextResponse.json(
    {
      error: "duplicate_operation",
      operation: {
        operationId: operation.operation_id,
        action: operation.action,
        phase: operation.phase,
        assurance: operation.assurance,
        createdAt: operation.created_at,
      },
    },
    { status: 409, headers: { "Cache-Control": "no-store" } },
  );
}

export function isUniqueViolation(error: unknown): boolean {
  return Boolean(error && typeof error === "object" && "code" in error && error.code === "23505");
}
