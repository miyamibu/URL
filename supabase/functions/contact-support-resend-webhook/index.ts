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

const MAX_WEBHOOK_BYTES = 64 * 1024;

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return jsonResponse({ error: "Method not allowed" }, 405);
  }

  const payload = await readBodyText(request);
  if (payload === null) return jsonResponse({ error: "payload_too_large" }, 413);
  let event: ResendWebhookEvent;
  try {
    event = new Webhook(requiredEnv("RESEND_WEBHOOK_SECRET")).verify(payload, svixHeaders(request)) as ResendWebhookEvent;
  } catch {
    return jsonResponse({ error: "Invalid webhook secret" }, 401);
  }

  const status = event.type ? STATUS_BY_EVENT[event.type] : undefined;
  const emailId = event.data?.email_id;
  const providerEventId = request.headers.get("svix-id")?.trim();
  if (!status || !emailId || !providerEventId) {
    return jsonResponse({ ignored: true }, 202);
  }

  const supabaseUrl = requiredEnv("SUPABASE_URL").replace(/\/+$/, "");
  const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  const eventAt = event?.created_at && !Number.isNaN(Date.parse(event.created_at))
    ? new Date(event.created_at).toISOString()
    : new Date().toISOString();

  const response = await fetch(
    `${supabaseUrl}/rest/v1/rpc/record_contact_support_delivery_event`,
    {
      method: "POST",
      headers: {
        apikey: serviceRoleKey,
        authorization: `Bearer ${serviceRoleKey}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        p_provider_event_id: providerEventId,
        p_email_id: emailId,
        p_event_type: event.type,
        p_delivery_status: status,
        p_event_at: eventAt,
        p_delivery_error: deliveryErrorFor(event),
      }),
    },
  );

  if (!response.ok) {
    return jsonResponse({ error: "delivery_event_record_failed" }, 500);
  }
  return jsonResponse({ accepted: true, status }, 200);
});

async function readBodyText(request: Request): Promise<string | null> {
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > MAX_WEBHOOK_BYTES) return null;
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength > MAX_WEBHOOK_BYTES) return null;
  return new TextDecoder().decode(bytes);
}

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
