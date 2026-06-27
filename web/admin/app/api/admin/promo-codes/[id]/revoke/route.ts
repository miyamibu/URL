import { NextRequest, NextResponse } from "next/server";
import { assertWritable, requireAdmin } from "@/lib/auth";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "優待コードを取り消せませんでした";
  return NextResponse.json({ error: message }, { status: 500 });
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

    const { data: revoked, error } = await supabase
      .from("promo_invite_codes")
      .update({
        delivery_status: "revoked",
        revoked_at: new Date().toISOString(),
        revoked_by: admin.id,
        revoked_reason: reason,
      })
      .eq("id", id)
      .is("revoked_at", null)
      .select("id")
      .maybeSingle();

    if (error) throw error;
    if (!revoked) {
      return NextResponse.json({ error: "取消対象の優待コードが見つからないか、すでに取消済みです" }, { status: 404 });
    }

    const { error: eventError } = await supabase.from("promo_invite_code_events").insert({
      code_id: id,
      event: "revoked",
      actor_user_id: admin.userId,
      detail: { reason },
    });
    if (eventError) throw eventError;

    return NextResponse.json({ ok: true });
  } catch (error) {
    return asErrorResponse(error);
  }
}
