import { NextRequest, NextResponse } from "next/server";
import { Webhook } from "svix";
import { requireEnv } from "@/lib/env";
import { createServiceSupabaseClient } from "@/lib/supabase";

type ResendWebhookEvent = {
  type?: string;
  created_at?: string;
  data?: {
    bounce?: {
      message?: string;
      subType?: string;
      type?: string;
      [key: string]: unknown;
    };
    email_id?: string;
    error?: string;
    message_id?: string;
    message?: string;
    reason?: string;
    from?: string;
    to?: string[];
    subject?: string;
    [key: string]: unknown;
  };
};

type DeliveryMapping = {
  status: "sent" | "delivered" | "delivery_delayed" | "bounced" | "complained" | "failed";
  event: "email_sent" | "email_delivered" | "email_delivery_delayed" | "email_bounced" | "email_complained" | "email_failed" | "email_suppressed";
};

const EVENT_MAPPING: Record<string, DeliveryMapping | undefined> = {
  "email.sent": { status: "sent", event: "email_sent" },
  "email.delivered": { status: "delivered", event: "email_delivered" },
  "email.delivery_delayed": { status: "delivery_delayed", event: "email_delivery_delayed" },
  "email.bounced": { status: "bounced", event: "email_bounced" },
  "email.complained": { status: "complained", event: "email_complained" },
  "email.failed": { status: "failed", event: "email_failed" },
  "email.suppressed": { status: "failed", event: "email_suppressed" },
};

function svixHeaders(request: NextRequest): Record<string, string> {
  return {
    "svix-id": request.headers.get("svix-id") ?? "",
    "svix-timestamp": request.headers.get("svix-timestamp") ?? "",
    "svix-signature": request.headers.get("svix-signature") ?? "",
  };
}

function svixId(request: NextRequest): string | null {
  const value = request.headers.get("svix-id")?.trim();
  return value || null;
}

function deliveryErrorFor(event: ResendWebhookEvent): string | null {
  if (event.type === "email.bounced" || event.type === "email.failed" || event.type === "email.suppressed") {
    const reason = event.data?.bounce?.message ?? event.data?.reason ?? event.data?.error ?? event.data?.message;
    return typeof reason === "string" && reason.trim() ? reason.trim() : event.type;
  }
  if (event.type === "email.complained") {
    return "Recipient marked the email as spam";
  }
  return null;
}

export async function POST(request: NextRequest) {
  const payload = await request.text();
  let event: ResendWebhookEvent;

  try {
    event = new Webhook(requireEnv("RESEND_WEBHOOK_SECRET")).verify(payload, svixHeaders(request)) as ResendWebhookEvent;
  } catch {
    return NextResponse.json({ error: "Invalid webhook signature" }, { status: 400 });
  }

  const mapping = event.type ? EVENT_MAPPING[event.type] : undefined;
  const emailId = event.data?.email_id;
  if (!mapping || !emailId) {
    return NextResponse.json({ ignored: true }, { status: 202 });
  }

  const eventAt = event.created_at && !Number.isNaN(Date.parse(event.created_at))
    ? new Date(event.created_at).toISOString()
    : new Date().toISOString();
  const supabase = createServiceSupabaseClient();
  const { data: result, error } = await supabase.rpc("record_resend_promo_delivery_event", {
    p_delivery_message_id: emailId,
    p_provider_event_id: svixId(request),
    p_event_type: event.type,
    p_delivery_status: mapping.status,
    p_event_name: mapping.event,
    p_event_at: eventAt,
    p_delivery_error: deliveryErrorFor(event),
    p_detail: {
      provider: "resend",
      email_id: emailId,
      message_id: event.data?.message_id ?? null,
      bounce: event.data?.bounce ?? null,
      error: deliveryErrorFor(event),
      from: event.data?.from ?? null,
      to: event.data?.to ?? null,
      subject: event.data?.subject ?? null,
      received_at: eventAt,
    },
  });

  if (error) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }

  return NextResponse.json(result ?? { accepted: true, matched: true, status: mapping.status });
}
