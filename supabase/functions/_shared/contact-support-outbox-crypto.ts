export const CONTACT_SUPPORT_ENVELOPE_VERSION = 1;
export const CONTACT_SUPPORT_ENVELOPE_ALGORITHM = "AES-256-GCM";

export type ContactSupportEncryptedEnvelope = {
  version: number;
  algorithm: string;
  nonce: string;
  ciphertext: string;
};

const textEncoder = new TextEncoder();

export async function encryptContactSupportPayload(
  payload: Record<string, unknown>,
  secret: string,
): Promise<{ envelope: ContactSupportEncryptedEnvelope; payloadHash: string }> {
  const plaintext = textEncoder.encode(JSON.stringify(payload));
  const key = await importEncryptionKey(secret, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: asArrayBuffer(nonce) },
      key,
      asArrayBuffer(plaintext),
    ),
  );
  return {
    envelope: {
      version: CONTACT_SUPPORT_ENVELOPE_VERSION,
      algorithm: CONTACT_SUPPORT_ENVELOPE_ALGORITHM,
      nonce: encodeBase64Url(nonce),
      ciphertext: encodeBase64Url(ciphertext),
    },
    payloadHash: await sha256Hex(plaintext),
  };
}

export async function decryptContactSupportPayload(
  value: unknown,
  secret: string,
): Promise<unknown> {
  const envelope = parseContactSupportEncryptedEnvelope(value);
  if (!envelope) throw new Error("invalid_contact_support_envelope");
  const nonce = decodeBase64(envelope.nonce);
  const ciphertext = decodeBase64(envelope.ciphertext);
  if (nonce.byteLength !== 12 || ciphertext.byteLength < 16) {
    throw new Error("invalid_contact_support_envelope");
  }
  const key = await importEncryptionKey(secret, ["decrypt"]);
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: asArrayBuffer(nonce) },
    key,
    asArrayBuffer(ciphertext),
  );
  return JSON.parse(new TextDecoder().decode(plaintext));
}

export function parseContactSupportEncryptedEnvelope(
  value: unknown,
): ContactSupportEncryptedEnvelope | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidate = value as Record<string, unknown>;
  if (
    candidate.version !== CONTACT_SUPPORT_ENVELOPE_VERSION ||
    candidate.algorithm !== CONTACT_SUPPORT_ENVELOPE_ALGORITHM ||
    !boundedBase64Url(candidate.nonce, 32) ||
    !boundedBase64Url(candidate.ciphertext, 32 * 1024)
  ) return null;
  return {
    version: CONTACT_SUPPORT_ENVELOPE_VERSION,
    algorithm: CONTACT_SUPPORT_ENVELOPE_ALGORITHM,
    nonce: candidate.nonce as string,
    ciphertext: candidate.ciphertext as string,
  };
}

export function validateEncryptionSecret(secret: string): void {
  decodeEncryptionKey(secret);
}

async function importEncryptionKey(
  secret: string,
  usages: KeyUsage[],
): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    asArrayBuffer(decodeEncryptionKey(secret)),
    { name: "AES-GCM" },
    false,
    usages,
  );
}

function decodeEncryptionKey(secret: string): Uint8Array {
  const normalized = secret.trim();
  if (/^[0-9a-f]{64}$/i.test(normalized)) {
    const bytes = new Uint8Array(32);
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Number.parseInt(
        normalized.slice(index * 2, index * 2 + 2),
        16,
      );
    }
    return bytes;
  }
  const decoded = decodeBase64(normalized);
  if (decoded.byteLength !== 32) throw new Error("invalid_encryption_key");
  return decoded;
}

function decodeBase64(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/").replace(
    /\s/g,
    "",
  );
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(
    /=+$/g,
    "",
  );
}

function boundedBase64Url(value: unknown, maxBytes: number): value is string {
  if (typeof value !== "string" || !value || value.length > maxBytes * 2) {
    return false;
  }
  return /^[A-Za-z0-9_-]+$/.test(value);
}

async function sha256Hex(value: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", asArrayBuffer(value));
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function asArrayBuffer(value: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(value.byteLength);
  copy.set(value);
  return copy.buffer;
}
