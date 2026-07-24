import { NextRequest, NextResponse } from "next/server";
import { requireAdmin } from "@/lib/auth";
import { requireEnv } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

type Params = {
  params: Promise<{ id: string }>;
};

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  console.error("admin promo delivery lookup failed", error instanceof Error ? error.name : "unknown");
  return NextResponse.json({ error: "配送状態を確認できませんでした" }, { status: 500 });
}

export async function GET(request: NextRequest, { params }: Params) {
  try {
    await requireAdmin(request);
    const { id } = await params;
    const supabase = createServiceSupabaseClient();
    const { data: code, error } = await supabase
      .from("promo_invite_codes")
      .select("id,target_email,delivery_provider,delivery_message_id,delivery_status,delivery_error")
      .eq("id", id)
      .single();

    if (error) throw error;
    if (!code.delivery_message_id) {
      return NextResponse.json({ error: "Resendのmessage idがありません" }, { status: 404 });
    }

    const response = await fetch(`https://api.resend.com/emails/${encodeURIComponent(code.delivery_message_id)}`, {
      headers: {
        Authorization: `Bearer ${requireEnv("RESEND_API_KEY")}`,
      },
      cache: "no-store",
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      return NextResponse.json({ error: "配送状態を取得できませんでした" }, { status: response.status });
    }

    return NextResponse.json({
      id: code.id,
      targetEmail: code.target_email,
      deliveryStatus: code.delivery_status,
      deliveryProvider: code.delivery_provider,
      deliveryMessageId: code.delivery_message_id,
      resend: {
        id: body.id ?? null,
        messageId: body.message_id ?? null,
        from: body.from ?? null,
        to: body.to ?? null,
        subject: body.subject ?? null,
        createdAt: body.created_at ?? null,
        lastEvent: body.last_event ?? null,
      },
    });
  } catch (error) {
    return asErrorResponse(error);
  }
}
