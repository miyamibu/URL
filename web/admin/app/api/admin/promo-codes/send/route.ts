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

    try {
      const delivery = await sendPromoEmail({ to: targetEmail, code, expiresAt, note });
      const { error: updateError } = await supabase
        .from("promo_invite_codes")
        .update({
          delivery_status: "sent",
          sent_at: new Date().toISOString(),
          delivery_provider: "resend",
          delivery_message_id: delivery.id ?? null,
          delivery_event_type: "email.sent",
          delivery_event_at: new Date().toISOString(),
          delivery_error: null,
        })
        .eq("id", codeId);

      if (updateError) throw updateError;

      await insertEvent(supabase, {
        code_id: codeId,
        event: "email_sent",
        actor_user_id: admin.userId,
        detail: { provider: "resend", message_id: delivery.id ?? null },
      });

      return NextResponse.json({
        id: codeId,
        targetEmail,
        code,
        redeemLink: promoLinkForCode(code),
        expiresAt,
      });
    } catch (sendError) {
      const message = sendError instanceof Error ? sendError.message : "メール送信に失敗しました";
      await supabase
        .from("promo_invite_codes")
        .update({
          delivery_status: "failed",
          revoked_at: new Date().toISOString(),
          revoked_by: admin.id,
          revoked_reason: "email_failed",
          delivery_provider: "resend",
          delivery_error: message,
        })
        .eq("id", codeId);

      await insertEvent(supabase, {
        code_id: codeId,
        event: "email_failed",
        actor_user_id: admin.userId,
        detail: { provider: "resend", error: message },
      });

      return NextResponse.json({ error: message }, { status: 502 });
    }
  } catch (error) {
    return asErrorResponse(error);
  }
}
