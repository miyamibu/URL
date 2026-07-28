import { NextRequest, NextResponse } from "next/server";
import { assertHighRisk, assertWritable, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";
import { recordAdminAudit, requiredAdminReason } from "@/lib/audit";
import { normalizedEmail } from "@/lib/env";
import { generatePromoCode, promoCodeHash, promoLinkForCode, sendPromoEmail } from "@/lib/promo";
import { createServiceSupabaseClient } from "@/lib/supabase";

const MAX_CODE_INSERT_ATTEMPTS = 5;
const PROMO_CODE_OPERATION = "promo_code_issue";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
type OperationStatus = "started" | "completed" | "failed";

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "優待コードを送信できませんでした");
}

function safeDeliveryErrorCode(): string {
  return "provider_request_failed";
}

async function insertEvent(
  supabase: ReturnType<typeof createServiceSupabaseClient>,
  input: {
    code_id: string;
    event: "issued" | "email_sent" | "email_failed";
    actor_user_id: string;
    detail: Record<string, unknown>;
  },
) {
  const { error } = await supabase.from("promo_invite_code_events").insert(input);
  if (error) throw error;
}

async function updateOperation(
  supabase: ReturnType<typeof createServiceSupabaseClient>,
  input: {
    adminUserId: string;
    operationId: string;
    status: OperationStatus;
    codeId: string | null;
  },
): Promise<void> {
  const { data, error } = await supabase
    .from("admin_operation_idempotency")
    .update({ status: input.status, code_id: input.codeId })
    .eq("admin_user_id", input.adminUserId)
    .eq("operation_id", input.operationId)
    .eq("operation", PROMO_CODE_OPERATION)
    .select("operation_id")
    .single();

  if (error) throw error;
  if (!data) throw new Error("管理操作の冪等性状態を更新できませんでした");
}

async function duplicateOperationResponse(
  supabase: ReturnType<typeof createServiceSupabaseClient>,
  adminUserId: string,
  operationId: string,
): Promise<Response> {
  const { data: operation, error: operationError } = await supabase
    .from("admin_operation_idempotency")
    .select("status,code_id,created_at,updated_at")
    .eq("admin_user_id", adminUserId)
    .eq("operation_id", operationId)
    .eq("operation", PROMO_CODE_OPERATION)
    .maybeSingle();
  if (operationError) throw operationError;

  let codeState: Record<string, unknown> | null = null;
  if (operation?.code_id) {
    const { data: code, error: codeError } = await supabase
      .from("promo_invite_codes")
      .select("id,delivery_status,delivery_event_type,expires_at,claimed_at,revoked_at")
      .eq("id", operation.code_id)
      .maybeSingle();
    if (codeError) throw codeError;
    if (code) {
      codeState = {
        id: code.id,
        deliveryStatus: code.delivery_status,
        deliveryEventType: code.delivery_event_type,
        expiresAt: code.expires_at,
        claimed: Boolean(code.claimed_at),
        revoked: Boolean(code.revoked_at),
      };
    }
  }

  return NextResponse.json(
    {
      error: "duplicate_operation",
      operation: operation
        ? {
            operationId,
            status: operation.status,
            createdAt: operation.created_at,
            updatedAt: operation.updated_at,
            code: codeState,
          }
        : { operationId, status: "unknown", code: null },
    },
    { status: 409, headers: { "Cache-Control": "no-store" } },
  );
}

export async function POST(request: NextRequest) {
  let codeId: string | null = null;
  let adminId: string | null = null;
  let operationId: string | null = null;
  let operationClaimed = false;
  let supabase: ReturnType<typeof createServiceSupabaseClient> | null = null;
  try {
    const admin = await requireAdmin(request);
    adminId = admin.id;
    assertWritable(admin);
    assertHighRisk(admin);

    const parsedBody = await request.json().catch(() => ({}));
    const body = parsedBody && typeof parsedBody === "object" && !Array.isArray(parsedBody)
      ? parsedBody as Record<string, unknown>
      : {};
    const requestedOperationId = typeof body.operationId === "string" ? body.operationId.trim() : "";
    if (!requestedOperationId) {
      return NextResponse.json({ error: "operation_id_required" }, { status: 400 });
    }
    if (!UUID_PATTERN.test(requestedOperationId)) {
      return NextResponse.json({ error: "operation_id_invalid" }, { status: 400 });
    }
    operationId = requestedOperationId;
    const targetEmail = normalizedEmail(String(body.targetEmail ?? ""));
    const note = String(body.note ?? "").trim() || null;
    const reason = requiredAdminReason(body.reason);
    const expiresInDays = Number(body.expiresInDays ?? 7);

    if (!targetEmail || !targetEmail.includes("@")) {
      return NextResponse.json({ error: "送信先メールアドレスを確認してください" }, { status: 400 });
    }
    if (!Number.isFinite(expiresInDays) || expiresInDays < 1 || expiresInDays > 90) {
      return NextResponse.json({ error: "有効期限は1日から90日の範囲で指定してください" }, { status: 400 });
    }

    supabase = createServiceSupabaseClient();
    const { error: claimError } = await supabase.from("admin_operation_idempotency").insert({
      admin_user_id: admin.id,
      operation_id: requestedOperationId,
      operation: PROMO_CODE_OPERATION,
    });
    if (claimError?.code === "23505") {
      return duplicateOperationResponse(supabase, admin.id, requestedOperationId);
    }
    if (claimError) throw claimError;
    operationClaimed = true;

    await recordAdminAudit({
      adminUserId: admin.id,
      action: "promo_code_issue_started",
      reason,
      operationId: requestedOperationId,
      phase: "started",
      assurance: admin.assurance,
    });

    const expiresAt = new Date(Date.now() + expiresInDays * 24 * 60 * 60 * 1000).toISOString();
    let code = "";

    for (let attempt = 0; attempt < MAX_CODE_INSERT_ATTEMPTS; attempt += 1) {
      code = generatePromoCode();
      const codeHash = promoCodeHash(code);

      const { data: inserted, error: insertError } = await supabase
        .from("promo_invite_codes")
        .insert({
          code_hash: codeHash,
          target_email: targetEmail,
          created_by: admin.id,
          expires_at: expiresAt,
          note,
          delivery_status: "pending",
        })
        .select("id")
        .single();

      if (!insertError) {
        codeId = inserted.id;
        break;
      }
      if (insertError.code !== "23505" || attempt === MAX_CODE_INSERT_ATTEMPTS - 1) {
        throw insertError;
      }
    }

    if (!codeId || !code) {
      throw new Error("優待コードを作成できませんでした");
    }

    await updateOperation(supabase, {
      adminUserId: admin.id,
      operationId: requestedOperationId,
      status: "started",
      codeId,
    });

    await insertEvent(supabase, {
      code_id: codeId,
      event: "issued",
      actor_user_id: admin.userId,
      detail: { target_email: targetEmail, expires_at: expiresAt },
    });

    let delivery: { id?: string };
    try {
      delivery = await sendPromoEmail({ to: targetEmail, code, expiresAt, note });
    } catch {
      const { error: recordFailureError } = await supabase.rpc("admin_record_promo_email_failed", {
        p_code_id: codeId,
        p_admin_id: admin.id,
        p_actor_user_id: admin.userId,
        p_error: safeDeliveryErrorCode(),
        p_event_at: new Date().toISOString(),
      });
      if (recordFailureError) throw recordFailureError;

      await updateOperation(supabase, {
        adminUserId: admin.id,
        operationId: requestedOperationId,
        status: "failed",
        codeId,
      });

      await recordAdminAudit({
        adminUserId: admin.id,
        action: "promo_code_issue_failed",
        reason,
        afterValue: { codeId, deliveryStatus: "failed" },
        operationId: requestedOperationId,
        phase: "failed",
        assurance: admin.assurance,
      });

      return NextResponse.json(
        { error: "優待コードのメール送信に失敗しました", operationId: requestedOperationId },
        { status: 502, headers: { "Cache-Control": "no-store" } },
      );
    }

    try {
      const { data: recordData, error: recordError } = await supabase.rpc("admin_record_promo_email_sent_audited", {
        p_code_id: codeId,
        p_admin_id: admin.id,
        p_actor_user_id: admin.userId,
        p_message_id: delivery.id ?? null,
        p_reason: reason,
        p_operation_id: requestedOperationId,
        p_assurance: admin.assurance,
        p_event_at: new Date().toISOString(),
      });

      if (recordError) {
        throw recordError;
      }
      if (!recordData) throw new Error("配送状態と監査結果を確認できませんでした");
    } catch {
      await updateOperation(supabase, {
        adminUserId: admin.id,
        operationId: requestedOperationId,
        status: "failed",
        codeId,
      });
      await recordAdminAudit({
        adminUserId: admin.id,
        action: "promo_code_issue_failed",
        reason,
        afterValue: {
          codeId,
          deliveryStatus: "needs_reconcile",
          deliveryMessageIdPresent: Boolean(delivery.id),
        },
        operationId: requestedOperationId,
        phase: "failed",
        assurance: admin.assurance,
      });
      return NextResponse.json(
        {
          id: codeId,
          operationId: requestedOperationId,
          needsReconcile: true,
          error: "メール送信後の保存結果を確認できませんでした。再送せず、既存操作を照合してください。",
        },
        { status: 503, headers: { "Cache-Control": "no-store" } },
      );
    }

    await updateOperation(supabase, {
      adminUserId: admin.id,
      operationId: requestedOperationId,
      status: "completed",
      codeId,
    });

    return NextResponse.json(
      {
        id: codeId,
        targetEmail,
        code,
        redeemLink: promoLinkForCode(code),
        expiresAt,
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    if (operationClaimed && supabase && adminId && operationId) {
      try {
        await updateOperation(supabase, {
          adminUserId: adminId,
          operationId,
          status: "failed",
          codeId,
        });
      } catch {
        // Preserve the original error; the row remains available for reconciliation.
      }
    }
    return asErrorResponse(error);
  }
}
