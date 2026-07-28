export type AdminRole = "owner" | "billing" | "moderator" | "readonly";

export type AdminCapability =
  | "promos.read"
  | "promos.issue"
  | "support.read"
  | "support.write"
  | "moderation.read"
  | "moderation.manage"
  | "users.search"
  | "admins.manage"
  | "audit.read";

export type AdminAssurance = {
  aal: "aal1" | "aal2";
  acr: string | null;
  methods: readonly string[];
  verifiedAt: number | null;
};

type AssuranceClaims = {
  aal?: unknown;
  acr?: unknown;
  amr?: unknown;
};

type AssuranceMethod = {
  method?: unknown;
  timestamp?: unknown;
};

const ROLE_CAPABILITIES: Record<AdminRole, readonly AdminCapability[]> = {
  owner: [
    "promos.read",
    "promos.issue",
    "support.read",
    "support.write",
    "moderation.read",
    "moderation.manage",
    "users.search",
    "admins.manage",
    "audit.read",
  ],
  billing: ["promos.read", "promos.issue", "users.search", "audit.read"],
  moderator: ["support.read", "support.write", "moderation.read", "moderation.manage", "audit.read"],
  readonly: ["promos.read", "support.read", "moderation.read", "audit.read"],
};

const TOTP_AMR_METHODS = new Set(["mfa/totp", "totp"]);

export function capabilitiesForRole(role: unknown): readonly AdminCapability[] | null {
  if (typeof role !== "string" || !(role in ROLE_CAPABILITIES)) return null;
  return ROLE_CAPABILITIES[role as AdminRole];
}

export function parseAdminAssurance(claims: AssuranceClaims): AdminAssurance | null {
  const aal = claims.aal === "aal1" || claims.aal === "aal2" ? claims.aal : null;
  if (!aal) return null;
  if (claims.acr !== undefined && claims.acr !== null && typeof claims.acr !== "string") return null;
  if (claims.amr !== undefined && !Array.isArray(claims.amr)) return null;

  const acr = typeof claims.acr === "string" && claims.acr.trim() ? claims.acr.trim() : null;
  const methods = new Set<string>();
  let verifiedAt: number | null = null;

  for (const entry of (claims.amr ?? []) as AssuranceMethod[]) {
    if (!entry || typeof entry.method !== "string") return null;
    const method = entry.method.trim().toLowerCase();
    if (!method) return null;
    methods.add(method);
    if (TOTP_AMR_METHODS.has(method)) {
      if (typeof entry.timestamp !== "number" || !Number.isFinite(entry.timestamp)) return null;
      verifiedAt = Math.max(verifiedAt ?? 0, entry.timestamp);
    }
  }

  return { aal, acr, methods: [...methods], verifiedAt };
}

export function hasWeakAssuranceContext(acr: string | null): boolean {
  return Boolean(acr && /(?:^|[^a-z])(aal1|loa1|single[-_ ]factor)(?:$|[^a-z])/i.test(acr));
}

export function stepUpExpiresAt(assurance: AdminAssurance, maxAgeSeconds: number): number | null {
  if (assurance.aal !== "aal2" || assurance.verifiedAt === null || hasWeakAssuranceContext(assurance.acr)) {
    return null;
  }
  return assurance.verifiedAt + maxAgeSeconds;
}

export function isStepUpCurrent(
  assurance: AdminAssurance,
  nowSeconds: number,
  maxAgeSeconds: number,
  maxClockSkewSeconds: number,
): boolean {
  const expiresAt = stepUpExpiresAt(assurance, maxAgeSeconds);
  if (expiresAt === null || assurance.verifiedAt === null) return false;
  const age = nowSeconds - assurance.verifiedAt;
  return age >= -maxClockSkewSeconds && nowSeconds <= expiresAt;
}
