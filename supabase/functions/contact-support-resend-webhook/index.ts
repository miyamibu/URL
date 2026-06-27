import { Webhook } from "npm:svix@1.96.1";

type ResendWebhookEvent = {
  type?: string;
  created_at?: string;
  data?: {
    email_id?: string;
    message_id?: string;
    error?: string;
    message?: string;
    reason?: string;
    bounce?: {
      message?: string;
      type?: string;
      subType?: string;
      [key: string]: unknown;
    };
    [key: string]: unknown;
  };
};

const STATUS_BY_EVENT: Record<string, string | undefined> = {
  "email.sent": "sent",
  "email.delivered": "delivered",
  "email.delivery_delayed": "delivery_delayed",
  "email.bounced": "bounced",
  "email.complained": "complained",
  "email.failed": "failed",
  "email.suppressed": "suppressed",
};

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  const payload = await request.text();
  let event: ResendWebhookEvent;
  try {
    event = new Webhook(requiredEnv("RESEND_WEBHOOK_SECRET")).verify(payload, svixHeaders(request)) as ResendWebhookEvent;
  } catch {
    return jsonResponse({ error: "Invalid webhook secret" }, 401);
  }

  const status = event.type ? STATUS_BY_EVENT[event.type] : undefined;
  const emailId = event.data?.email_id;
  if (!status || !emailId) {
    return jsonResponse({ ignored: true }, 202);
  }

  const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
  const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  const eventAt = event?.created_at && !Number.isNaN(Date.parse(event.created_at))
    ? new Date(event.created_at).toISOString()
    : new Date().toISOString();

  const response = await fetch(
    `${supabaseUrl}/rest/v1/contact_support_requests?delivery_provider=eq.resend&delivery_message_id=eq.${encodeURIComponent(emailId)}`,
    {
      method: "PATCH",
      headers: {
        apikey: serviceRoleKey,
        authorization: `Bearer ${serviceRoleKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        delivery_status: status,
        delivery_event_type: event.type,
        delivery_event_at: eventAt,
        delivery_error: deliveryErrorFor(event),
      }),
    },
  );

  if (!response.ok) {
    return jsonResponse({ error: "Failed to update delivery status" }, 500);
  }
  return jsonResponse({ accepted: true, status }, 200);
});

function svixHeaders(request: Request): Record<string, string> {
  return {
    "svix-id": request.headers.get("svix-id") ?? "",
    "svix-timestamp": request.headers.get("svix-timestamp") ?? "",
    "svix-signature": request.headers.get("svix-signature") ?? "",
  };
}

function deliveryErrorFor(event: ResendWebhookEvent): string | null {
  if (event.type === "email.complained") return "Recipient marked the email as spam";
  if (event.type === "email.bounced" || event.type === "email.failed" || event.type === "email.suppressed") {
    const reason = event.data?.bounce?.message ?? event.data?.reason ?? event.data?.error ?? event.data?.message;
    return typeof reason === "string" && reason.trim() ? reason.trim() : event.type;
  }
  return null;
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Missing required env: ${name}`);
  return value;
}

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
