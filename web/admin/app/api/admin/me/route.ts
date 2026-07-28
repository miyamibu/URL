import { NextRequest, NextResponse } from "next/server";
import { requireAdmin } from "@/lib/auth";

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
        },
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    if (error instanceof Response) return error;
    return NextResponse.json({ error: "管理者情報を取得できませんでした" }, { status: 500 });
  }
}
