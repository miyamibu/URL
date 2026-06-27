import crypto from "crypto";
import { optionalEnv, requireEnv } from "./env";

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const CODE_GROUP_LENGTH = 4;
const CODE_GROUPS = 4;

export function normalizePromoCode(code: string): string {
  return code.trim().replace(/\s+/g, "").toUpperCase();
}

export function generatePromoCode(): string {
  const length = CODE_GROUP_LENGTH * CODE_GROUPS;
  const chars: string[] = [];
  while (chars.length < length) {
    const byte = crypto.randomBytes(1)[0];
    const fairMax = Math.floor(256 / CODE_ALPHABET.length) * CODE_ALPHABET.length;
    if (byte >= fairMax) continue;
    chars.push(CODE_ALPHABET[byte % CODE_ALPHABET.length]);
  }
  const groups: string[] = [];
  for (let index = 0; index < chars.length; index += CODE_GROUP_LENGTH) {
    groups.push(chars.slice(index, index + CODE_GROUP_LENGTH).join(""));
  }
  return `RNBM ${groups.join(" ")}`;
}

export function promoCodeHash(code: string): string {
  return crypto.createHash("sha256").update(normalizePromoCode(code)).digest("hex");
}

export function promoLinkForCode(code: string): string {
  const baseUrl = (process.env.PROMO_LINK_BASE_URL ?? "https://miyamibu.xyz").replace(/\/+$/, "");
  return `${baseUrl}/promo#code=${encodeURIComponent(code)}`;
}

function promoLandingLink(): string {
  const baseUrl = (process.env.PROMO_LINK_BASE_URL ?? "https://miyamibu.xyz").replace(/\/+$/, "");
  return `${baseUrl}/promo`;
}

export async function sendPromoEmail(input: {
  to: string;
  code: string;
  expiresAt: string;
  note?: string | null;
}) {
  const apiKey = requireEnv("RESEND_API_KEY");
  const from = requireEnv("PROMO_EMAIL_FROM");
  const configuredReplyTo = optionalEnv("PROMO_EMAIL_REPLY_TO") ?? undefined;
  const replyTo =
    configuredReplyTo && configuredReplyTo.toLowerCase() !== input.to.toLowerCase()
      ? configuredReplyTo
      : undefined;
  const subject = "URL Saver code";
  const expiresAtText = new Date(input.expiresAt).toLocaleString("ja-JP", { timeZone: "Asia/Tokyo" });
  const displayCode = input.code.trim().replace(/\s+/g, " ");
  const text = [
    "URL Saver code",
    "",
    `Code: ${displayCode}`,
    `Expires: ${expiresAtText}`,
    "",
    "Open the app and enter this code.",
  ].join("\n");
  const html = `<!doctype html>
<html>
  <body style="margin:0;padding:0;background:#f6f7f9;color:#172033;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
    <div style="max-width:560px;margin:0 auto;padding:28px 18px;">
      <div style="background:#ffffff;border:1px solid #e3e7ee;border-radius:12px;padding:24px;">
        <p style="margin:0 0 8px;font-size:13px;color:#667085;">URL Saver</p>
        <h1 style="margin:0 0 16px;font-size:22px;line-height:1.35;color:#172033;">Your code</h1>
        <p style="margin:0 0 18px;font-size:15px;line-height:1.6;color:#344054;">Open the app and enter this code.</p>
        <p style="margin:0 0 18px;font-size:22px;line-height:1.5;font-weight:700;letter-spacing:1px;color:#172033;">${displayCode}</p>
        <p style="margin:0;font-size:14px;line-height:1.6;color:#475467;">Expires: ${expiresAtText}</p>
      </div>
    </div>
  </body>
</html>`;

  const response = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      from,
      to: input.to,
      ...(replyTo ? { reply_to: replyTo } : {}),
      subject,
      text,
      html,
    }),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const message = typeof body?.message === "string" ? body.message : `Resend failed: ${response.status}`;
    throw new Error(message);
  }
  return body as { id?: string };
}
