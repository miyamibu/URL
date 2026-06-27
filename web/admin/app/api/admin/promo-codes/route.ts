import { NextRequest, NextResponse } from "next/server";
import { requireAdmin } from "@/lib/auth";
import { createServiceSupabaseClient } from "@/lib/supabase";

function asErrorResponse(error: unknown): Response {
  if (error instanceof Response) return error;
  const message = error instanceof Error ? error.message : "管理APIでエラーが発生しました";
  return NextResponse.json({ error: message }, { status: 500 });
}

function deriveStatus(row: Record<string, unknown>): string {
  if (row.revoked_at) return "revoked";
  if (row.claimed_at) return "redeemed";
  if (row.expires_at && new Date(String(row.expires_at)).getTime() <= Date.now()) return "expired";
  return String(row.delivery_status ?? "sent");
}

export async function GET(request: NextRequest) {
  try {
    await requireAdmin(request);
    const supabase = createServiceSupabaseClient();
    const { data, error } = await supabase
      .from("promo_invite_codes")
      .select(
        "id,target_email,created_by,created_at,expires_at,claimed_by,claimed_at,revoked_at,note,delivery_status,sent_at,delivery_provider,delivery_message_id,delivery_event_type,delivery_event_at,delivery_error,revoked_reason",
      )
      .order("created_at", { ascending: false })
      .limit(100);

    if (error) throw error;

    return NextResponse.json({
      codes: (data ?? []).map((row) => ({
        ...row,
        status_label: deriveStatus(row),
      })),
    });
  } catch (error) {
    return asErrorResponse(error);
  }
}
