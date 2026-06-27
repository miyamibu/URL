import { NextRequest, NextResponse } from "next/server";
import { assertWritable, requireAdmin } from "@/lib/auth";
import { normalizedEmail } from "@/lib/env";
import { generatePromoCode, promoCodeHash, promoLinkForCode, sendPromoEmail } from "@/lib/promo";
import { createServiceSupabaseClient } from "@/lib/supabase";

const MAX_CODE_INSERT_ATTEMPTS = 5;

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "優待コードを送信できませんでした";
  return NextResponse.json({ error: message }, { status: 500 });
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

function rpcMessage(error: unknown): string {
  if (error && typeof error === "object" && "message" in error && typeof error.message === "string") {
    return error.message;
  }
  return error instanceof Error ? error.message : "優待コードを送信できませんでした";
}

export async function POST(request: NextRequest) {
  let codeId: string | null = null;
  try {
    const admin = await requireAdmin(request);
    assertWritable(admin);

    const body = await request.json().catch(() => ({}));
    const targetEmail = normalizedEmail(String(body.targetEmail ?? ""));
    const note = String(body.note ?? "").trim() || null;
    const expiresInDays = Number(body.expiresInDays ?? 7);

    if (!targetEmail || !targetEmail.includes("@")) {
      return NextResponse.json({ error: "送信先メールアドレスを確認してください" }, { status: 400 });
    }
    if (!Number.isFinite(expiresInDays) || expiresInDays < 1 || expiresInDays > 90) {
      return NextResponse.json({ error: "有効期限は1日から90日の範囲で指定してください" }, { status: 400 });
    }

    const supabase = createServiceSupabaseClient();
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

    await insertEvent(supabase, {
      code_id: codeId,
      event: "issued",
      actor_user_id: admin.userId,
      detail: { target_email: targetEmail, expires_at: expiresAt },
    });

    let delivery: { id?: string };
    try {
      delivery = await sendPromoEmail({ to: targetEmail, code, expiresAt, note });
    } catch (sendError) {
      const message = sendError instanceof Error ? sendError.message : "メール送信に失敗しました";
      const { error: recordFailureError } = await supabase.rpc("admin_record_promo_email_failed", {
        p_code_id: codeId,
        p_admin_id: admin.id,
        p_actor_user_id: admin.userId,
        p_error: message,
        p_event_at: new Date().toISOString(),
      });
      if (recordFailureError) throw recordFailureError;

      return NextResponse.json({ error: message }, { status: 502 });
    }

    try {
      const { error: recordError } = await supabase.rpc("admin_record_promo_email_sent", {
        p_code_id: codeId,
        p_actor_user_id: admin.userId,
        p_message_id: delivery.id ?? null,
        p_event_at: new Date().toISOString(),
      });

      if (recordError) {
        throw recordError;
      }
    } catch (recordError) {
      return NextResponse.json(
        {
          id: codeId,
          targetEmail,
          expiresAt,
          deliveryMessageId: delivery.id ?? null,
          needsReconcile: true,
          error: `メールは送信済みですが、配送状態の保存に失敗しました: ${rpcMessage(recordError)}`,
        },
        { status: 503 },
      );
    }

    return NextResponse.json({
      id: codeId,
      targetEmail,
      code,
      redeemLink: promoLinkForCode(code),
      expiresAt,
    });
  } catch (error) {
    return asErrorResponse(error);
  }
}
