import { NextRequest, NextResponse } from "next/server";
import { assertCapability, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";
import { requireEnv } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

type Params = {
  params: Promise<{ id: string }>;
};

function asErrorResponse(error: unknown): Response {
  return adminApiError(error, "配送状態を確認できませんでした");
}

export async function GET(request: NextRequest, { params }: Params) {
  try {
    const admin = await requireAdmin(request);
    assertCapability(admin, "promos.read");
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
      return NextResponse.json(
        { error: "配送事業者の状態を取得できませんでした" },
        { status: 502, headers: { "Cache-Control": "no-store" } },
      );
    }

    return NextResponse.json(
      {
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
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return asErrorResponse(error);
  }
}
