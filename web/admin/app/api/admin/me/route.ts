import { NextRequest, NextResponse } from "next/server";
import { adminStepUpExpiresAt, requireAdmin } from "@/lib/auth";
import { adminApiError } from "@/lib/api-error";

export async function GET(request: NextRequest) {
  try {
    const admin = await requireAdmin(request);
    return NextResponse.json(
      {
        admin: {
          email: admin.email,
          role: admin.role,
          capabilities: admin.capabilities,
          assurance: admin.assurance,
          stepUpExpiresAt: adminStepUpExpiresAt(admin),
        },
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return adminApiError(error, "管理者情報を取得できませんでした");
  }
}
