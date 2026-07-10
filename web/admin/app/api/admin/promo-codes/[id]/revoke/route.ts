import { NextRequest, NextResponse } from "next/server";
import { assertWritable, requireAdmin } from "@/lib/auth";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "優待コードを取り消せませんでした";
  return NextResponse.json({ error: message }, { status: 500 });
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
    const { id } = await context.params;
    const body = await request.json().catch(() => ({}));
    const reason = String(body.reason ?? "admin_revoked").trim() || "admin_revoked";
    const supabase = createServiceSupabaseClient();

    const { error } = await supabase.rpc("admin_revoke_promo_invite_code", {
      p_code_id: id,
      p_admin_id: admin.id,
      p_actor_user_id: admin.userId,
      p_reason: reason,
      p_event_at: new Date().toISOString(),
    });
    if (error) {
      const message = rpcErrorMessage(error);
      if (message.includes("promo_code_already_claimed")) {
        return NextResponse.json({ error: "使用済みの優待コードは取り消せません" }, { status: 409 });
      }
      if (message.includes("promo_code_not_found") || message.includes("promo_code_already_revoked")) {
        return NextResponse.json({ error: "取消対象の優待コードが見つからないか、すでに取消済みです" }, { status: 404 });
      }
      throw error;
    }

    return NextResponse.json({ ok: true });
  } catch (error) {
    return asErrorResponse(error);
  }
}
