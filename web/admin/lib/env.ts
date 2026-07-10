export function requireEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(`Missing required env: ${name}`);
  }
  return value;
}

export function optionalEnv(name: string): string | null {
  const value = process.env[name]?.trim();
  return value ? value : null;
}

export function normalizedEmail(value: string): string {
  return value.trim().toLowerCase();
}

export function bootstrapEmails(): Set<string> {
  return new Set(
    (process.env.URLSAVER_ADMIN_BOOTSTRAP_EMAILS ?? "")
      .split(",")
      .map(normalizedEmail)
      .filter(Boolean),
  );
}
