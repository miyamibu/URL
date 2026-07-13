import crypto from "node:crypto";
import { optionalEnv, requireEnv } from "./env";
import { createServiceSupabaseClient } from "./supabase";

export const RINBAM_MCP_PROTOCOL_VERSION = "2025-11-25";
const SUPPORTED_MCP_PROTOCOL_VERSIONS = [RINBAM_MCP_PROTOCOL_VERSION, "2025-06-18", "2025-03-26"] as const;

export type RinbamMcpJsonRpcId = string | number | null;

export type RinbamMcpJsonRpcRequest = {
  jsonrpc: "2.0";
  id?: RinbamMcpJsonRpcId;
  method: string;
  params?: Record<string, unknown>;
};

export type RinbamMcpJsonRpcResponse = {
  jsonrpc: "2.0";
  id: RinbamMcpJsonRpcId;
  result?: Record<string, unknown>;
  error?: {
    code: number;
    message: string;
    data?: Record<string, unknown>;
  };
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isJsonRpcId(value: unknown): value is RinbamMcpJsonRpcId {
  return value === null || typeof value === "string" || (typeof value === "number" && Number.isFinite(value));
}

export function isSupportedMcpProtocolVersion(value: string): boolean {
  return (SUPPORTED_MCP_PROTOCOL_VERSIONS as readonly string[]).includes(value);
}

export function supportedMcpProtocolVersions(): readonly string[] {
  return SUPPORTED_MCP_PROTOCOL_VERSIONS;
}

export function isRinbamMcpJsonRpcRequest(body: unknown): body is RinbamMcpJsonRpcRequest {
  if (!isRecord(body) || body.jsonrpc !== "2.0" || typeof body.method !== "string" || body.method.length === 0) {
    return false;
  }
  if ("id" in body && !isJsonRpcId(body.id)) return false;
  if ("params" in body && body.params !== undefined && !isRecord(body.params)) return false;
  return true;
}

export function isRinbamMcpLegacyRestRequest(
  body: unknown,
): body is { tool: string; args?: Record<string, unknown> } {
  if (!isRecord(body) || typeof body.tool !== "string" || body.tool.length === 0) return false;
  return !("jsonrpc" in body) && (!body.args || isRecord(body.args));
}

export function isRinbamMcpLegacyRestEnabled(): boolean {
  return optionalEnv("URLSAVER_MCP_LEGACY_REST_ENABLED") === "true";
}

export class RinbamMcpJsonRpcError extends Error {
  constructor(
    public readonly code: number,
    message: string,
    public readonly data?: Record<string, unknown>,
  ) {
    super(message);
    this.name = "RinbamMcpJsonRpcError";
  }
}

export class RinbamMcpToolNotFoundError extends Error {
  constructor(toolName: string) {
    super(`unknown_tool:${toolName}`);
    this.name = "RinbamMcpToolNotFoundError";
  }
}

type ToolAnnotation = {
  readOnlyHint: true;
  destructiveHint: false;
  openWorldHint: false;
  idempotentHint: true;
};

export type RinbamMcpToolDescriptor = {
  name: string;
  title: string;
  description: string;
  inputSchema: Record<string, unknown>;
  annotations: ToolAnnotation;
};

const readOnlyAnnotations: ToolAnnotation = {
  readOnlyHint: true,
  destructiveHint: false,
  openWorldHint: false,
  idempotentHint: true,
};

const SAVED_SNAPSHOT_NOTICE = "保存時点の情報であり、現在の内容とは異なる可能性があります";
const MCP_RATE_LIMIT_WINDOW_MS = 60_000;
const MCP_RATE_LIMIT_MAX_REQUESTS = 60;
const rateLimitBuckets = new Map<string, { windowStart: number; count: number }>();

export function isRinbamMcpEnabled() {
  return optionalEnv("URLSAVER_MCP_ENABLED") === "true";
}

export const rinbamMcpTools: RinbamMcpToolDescriptor[] = [
  {
    name: "search",
    title: "Search saved links",
    description: "Searches the authenticated user's personal saved links without modifying data.",
    inputSchema: {
      type: "object",
      properties: {
        query: { type: "string" },
        limit: { type: "integer", minimum: 1, maximum: 20 },
        includeArchived: { type: "boolean", default: false },
        includeSharedTags: { type: "boolean", default: false },
      },
      required: ["query"],
      additionalProperties: false,
    },
    annotations: readOnlyAnnotations,
  },
  {
    name: "fetch",
    title: "Fetch saved link",
    description: "Fetches one authenticated user's saved link summary by opaque publicSafeId.",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", minLength: 16 } },
      required: ["id"],
      additionalProperties: false,
    },
    annotations: readOnlyAnnotations,
  },
  {
    name: "rinbam.list_tags",
    title: "List local personal tags",
    description: "Lists local personal-link tags synced for ChatGPT search.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: readOnlyAnnotations,
  },
  {
    name: "rinbam.get_ai_receipt",
    title: "Get AI receipt",
    description: "Returns AI receipt metadata when local receipt sync is available; never returns raw prompts or bodies.",
    inputSchema: {
      type: "object",
      properties: { id: { type: "string", minLength: 1 } },
      required: ["id"],
      additionalProperties: false,
    },
    annotations: readOnlyAnnotations,
  },
  {
    name: "rinbam.list_recent_saved_links",
    title: "List recent saved links",
    description: "Lists recent authenticated personal saved links without modifying data.",
    inputSchema: {
      type: "object",
      properties: {
        limit: { type: "integer", minimum: 1, maximum: 20 },
        includeArchived: { type: "boolean", default: false },
      },
      additionalProperties: false,
    },
    annotations: readOnlyAnnotations,
  },
];

export function validateRinbamMcpToolDescriptors(tools = rinbamMcpTools) {
  for (const tool of tools) {
    const annotations = tool.annotations;
    if (
      annotations?.readOnlyHint !== true ||
      annotations?.destructiveHint !== false ||
      annotations?.openWorldHint !== false ||
      annotations?.idempotentHint !== true
    ) {
      throw new Error(`Invalid MCP annotations for ${tool.name}`);
    }
  }
}

function jsonRpcResult(id: RinbamMcpJsonRpcId, result: Record<string, unknown>): RinbamMcpJsonRpcResponse {
  return { jsonrpc: "2.0", id, result };
}

function toolCallResult(payload: Record<string, unknown>, isError = false): Record<string, unknown> {
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    structuredContent: payload,
    ...(isError ? { isError: true } : {}),
  };
}

export function parseRinbamMcpJsonRpcRequest(body: unknown): RinbamMcpJsonRpcRequest {
  if (!isRinbamMcpJsonRpcRequest(body)) {
    throw new RinbamMcpJsonRpcError(-32600, "Invalid Request");
  }
  return body;
}

export async function callRinbamMcpTool(
  ctx: RinbamMcpContext,
  toolName: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  switch (toolName) {
    case "search":
      return searchRinbamLinks(ctx, args);
    case "fetch":
      return fetchRinbamLink(ctx, args);
    case "rinbam.list_tags":
      return listRinbamTags(ctx);
    case "rinbam.get_ai_receipt":
      return getAiReceipt(args);
    case "rinbam.list_recent_saved_links":
      return listRecentSavedLinks(ctx, args);
    default:
      throw new RinbamMcpToolNotFoundError(toolName);
  }
}

export async function handleRinbamMcpJsonRpcRequest(
  request: RinbamMcpJsonRpcRequest,
  ctx: RinbamMcpContext,
): Promise<RinbamMcpJsonRpcResponse | null> {
  const parsed = parseRinbamMcpJsonRpcRequest(request);
  if (!("id" in parsed) || parsed.id === undefined) {
    if (
      parsed.method === "notifications/initialized" ||
      parsed.method === "notifications/cancelled" ||
      parsed.method === "notifications/progress"
    ) {
      return null;
    }
    throw new RinbamMcpJsonRpcError(-32600, "Requests require an id");
  }

  const id = parsed.id;
  switch (parsed.method) {
    case "initialize": {
      const requestedVersion = parsed.params?.protocolVersion;
      if (typeof requestedVersion !== "string" || !isSupportedMcpProtocolVersion(requestedVersion)) {
        throw new RinbamMcpJsonRpcError(-32602, "Unsupported protocol version", {
          supported: { versions: [...SUPPORTED_MCP_PROTOCOL_VERSIONS] },
        });
      }
      return jsonRpcResult(id, {
        protocolVersion: requestedVersion,
        capabilities: { tools: {} },
        serverInfo: {
          name: "rinbam-readonly-links",
          version: "0.2.0",
        },
      });
    }
    case "ping":
      return jsonRpcResult(id, {});
    case "tools/list":
      return jsonRpcResult(id, { tools: rinbamMcpTools });
    case "tools/call": {
      const params = parsed.params;
      const toolName = params?.name;
      const args = params?.arguments;
      if (typeof toolName !== "string" || (args !== undefined && !isRecord(args))) {
        throw new RinbamMcpJsonRpcError(-32602, "Invalid tools/call parameters");
      }
      try {
        const payload = await callRinbamMcpTool(ctx, toolName, args ?? {});
        return jsonRpcResult(id, toolCallResult(payload));
      } catch (error) {
        if (error instanceof RinbamMcpInputError) {
          return jsonRpcResult(id, toolCallResult({ error: error.message }, true));
        }
        throw error;
      }
    }
    case "notifications/initialized":
      return jsonRpcResult(id, {});
    default:
      throw new RinbamMcpJsonRpcError(-32601, "Method not found");
  }
}

type PersonalSavedLinkRow = {
  id: string;
  effective_title: string | null;
  open_url: string | null;
  normalized_url: string | null;
  normalized_host: string | null;
  memo: string | null;
  body_summary: string | null;
  description: string | null;
  fetched_author_name: string | null;
  fetched_body_kind: string | null;
  service_type: string | null;
  record_state: string | null;
  metadata_state: string | null;
  metadata_error: string | null;
  source_created_at: string | null;
  source_updated_at: string | null;
  archived_at: string | null;
  content_fetch_allowed: boolean | null;
};

type TagRow = { id: string; name: string };
type TagRefRow = { link_id: string; tag_id: string };

export type RinbamMcpContext = {
  userId: string;
  email: string | null;
  token: string;
};

export function bearerToken(authorizationHeader: string | null): string | null {
  const match = (authorizationHeader ?? "").match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || null;
}

export async function requireRinbamMcpUser(authorizationHeader: string | null): Promise<RinbamMcpContext> {
  const token = bearerToken(authorizationHeader);
  if (!token) {
    throw new RinbamMcpAuthError("auth_required");
  }
  const supabase = createServiceSupabaseClient();
  const { data, error } = await supabase.auth.getUser(token);
  if (error || !data.user) {
    throw new RinbamMcpAuthError("invalid_token");
  }
  return {
    userId: data.user.id,
    email: data.user.email ?? null,
    token,
  };
}

export class RinbamMcpAuthError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RinbamMcpAuthError";
  }
}

export class RinbamMcpInputError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RinbamMcpInputError";
  }
}

export class RinbamMcpRateLimitError extends Error {
  constructor(message = "rate_limited") {
    super(message);
    this.name = "RinbamMcpRateLimitError";
  }
}

export function checkRinbamMcpRateLimit(ctx: RinbamMcpContext, now = Date.now()) {
  const bucket = rateLimitBuckets.get(ctx.userId);
  if (!bucket || now - bucket.windowStart >= MCP_RATE_LIMIT_WINDOW_MS) {
    rateLimitBuckets.set(ctx.userId, { windowStart: now, count: 1 });
    return;
  }
  if (bucket.count >= MCP_RATE_LIMIT_MAX_REQUESTS) {
    throw new RinbamMcpRateLimitError();
  }
  bucket.count += 1;
}

export function publicSafeId(userId: string, rawId: string): string {
  const secret = requireEnv("URLSAVER_MCP_ID_SECRET");
  return crypto
    .createHmac("sha256", secret)
    .update(`${userId}:${rawId}`)
    .digest("hex")
    .slice(0, 32);
}

function clampLimit(value: unknown, fallback = 10): number {
  const parsed = Number(value ?? fallback);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(Math.max(Math.trunc(parsed), 1), 20);
}

function safeString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function safeText(value: string | null | undefined, maxLength = 1200): string {
  const trimmed = (value ?? "")
    .replace(/\u0000/g, "")
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "[redacted:email]")
    .replace(/\b(?:refresh_token|access_token|service_role|sb_secret|token)\s*[:=]\s*["']?[A-Za-z0-9._~+/=-]{8,}/gi, "[redacted:token]")
    .trim();
  return trimmed.length > maxLength ? `${trimmed.slice(0, maxLength - 1)}…` : trimmed;
}

function rejectSharedTagOptIn(args: Record<string, unknown>) {
  if (args.includeSharedTags === true) {
    throw new RinbamMcpInputError("include_shared_tags_requires_explicit_scope");
  }
}

function searchableText(row: PersonalSavedLinkRow, tags: string[]): string {
  return [
    row.effective_title,
    row.open_url,
    row.normalized_url,
    row.normalized_host,
    row.memo,
    row.body_summary,
    row.description,
    row.fetched_author_name,
    row.fetched_body_kind,
    row.service_type,
    ...tags,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

async function loadRows(ctx: RinbamMcpContext, maxRows = 200) {
  const supabase = createServiceSupabaseClient();
  const { data: links, error: linkError } = await supabase
    .from("personal_saved_links")
    .select(
      "id,effective_title,open_url,normalized_url,normalized_host,memo,body_summary,description,fetched_author_name,fetched_body_kind,service_type,record_state,metadata_state,metadata_error,source_created_at,source_updated_at,archived_at,content_fetch_allowed",
    )
    .eq("user_id", ctx.userId)
    .is("deleted_at", null)
    .is("disabled_at", null)
    .order("source_updated_at", { ascending: false })
    .limit(maxRows);
  if (linkError) throw linkError;

  const linkRows = (links ?? []) as PersonalSavedLinkRow[];
  const linkIds = linkRows.map((row) => row.id);
  const { data: tags, error: tagError } = await supabase
    .from("personal_saved_link_tags")
    .select("id,name")
    .eq("user_id", ctx.userId)
    .is("deleted_at", null);
  if (tagError) throw tagError;

  const tagRows = (tags ?? []) as TagRow[];
  const tagNameById = new Map(tagRows.map((tag) => [tag.id, tag.name]));
  const tagNamesByLinkId = new Map<string, string[]>();
  if (linkIds.length > 0) {
    const { data: refs, error: refError } = await supabase
      .from("personal_saved_link_tag_refs")
      .select("link_id,tag_id")
      .eq("user_id", ctx.userId)
      .in("link_id", linkIds)
      .is("deleted_at", null);
    if (refError) throw refError;
    for (const ref of (refs ?? []) as TagRefRow[]) {
      const tagName = tagNameById.get(ref.tag_id);
      if (!tagName) continue;
      const names = tagNamesByLinkId.get(ref.link_id) ?? [];
      names.push(tagName);
      tagNamesByLinkId.set(ref.link_id, names);
    }
  }

  return { links: linkRows, tagRows, tagNamesByLinkId };
}

function toSearchResult(ctx: RinbamMcpContext, row: PersonalSavedLinkRow, tags: string[]) {
  return {
    id: publicSafeId(ctx.userId, row.id),
    title: row.effective_title || row.normalized_host || "保存したリンク",
    url: row.open_url || row.normalized_url || "",
    bodyKind: row.fetched_body_kind,
    author: row.fetched_author_name,
    tags: tags.slice().sort(),
    createdAt: row.source_created_at,
    matchReason: "personal_saved_links",
    aiEligible: row.record_state === "ACTIVE",
    sharedTagBoundary: "local_personal_link_sync_only",
    rawBodyReturned: false,
  };
}

export async function searchRinbamLinks(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  rejectSharedTagOptIn(args);
  const query = safeString(args.query).toLowerCase();
  const limit = clampLimit(args.limit);
  const includeArchived = args.includeArchived === true;
  const { links, tagNamesByLinkId } = await loadRows(ctx);

  const results = links
    .filter((row) => includeArchived || row.record_state !== "ARCHIVED")
    .filter((row) => {
      if (!query) return true;
      return searchableText(row, tagNamesByLinkId.get(row.id) ?? []).includes(query);
    })
    .slice(0, limit)
    .map((row) => toSearchResult(ctx, row, tagNamesByLinkId.get(row.id) ?? []));

  return { results, includeSharedTags: false, rawBodyReturned: false };
}

export async function listRecentSavedLinks(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  rejectSharedTagOptIn(args);
  return searchRinbamLinks(ctx, { query: "", limit: args.limit, includeArchived: args.includeArchived });
}

export async function listRinbamTags(ctx: RinbamMcpContext) {
  const { tagRows } = await loadRows(ctx, 1);
  return {
    tags: tagRows
      .map((tag) => ({ id: publicSafeId(ctx.userId, tag.id), name: tag.name, sharedTagBoundary: "local_only" }))
      .sort((a, b) => a.name.localeCompare(b.name)),
    rawBodyReturned: false,
  };
}

export async function fetchRinbamLink(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  const id = safeString(args.id);
  const { links, tagNamesByLinkId } = await loadRows(ctx, 500);
  const row = links.find((candidate) => publicSafeId(ctx.userId, candidate.id) === id);
  if (!row) return { id, found: false };
  const tags = tagNamesByLinkId.get(row.id) ?? [];
  const title = row.effective_title || row.normalized_host || "保存したリンク";
  const hasSavedMetadata = Boolean(
    row.source_updated_at ||
      row.body_summary ||
      row.description ||
      row.fetched_author_name ||
      row.fetched_body_kind,
  );
  const text = [
    `# ${title}`,
    `URL: ${row.open_url || row.normalized_url || ""}`,
    `Service: ${row.service_type ?? ""}`,
    `State: ${row.record_state ?? ""}`,
    tags.length > 0 ? `Tags: ${tags.slice().sort().join(", ")}` : "Tags: none",
    row.body_summary ? `Summary: ${safeText(row.body_summary)}` : null,
    row.description ? `Description: ${safeText(row.description)}` : null,
    row.memo ? `Memo excerpt: ${safeText(row.memo)}` : null,
    hasSavedMetadata ? `Saved snapshot notice: ${SAVED_SNAPSHOT_NOTICE}` : null,
  ]
    .filter(Boolean)
    .join("\n");
  return {
    id,
    title,
    text,
    url: row.open_url || row.normalized_url || "",
    metadata: {
      recordState: row.record_state,
      metadataState: row.metadata_state,
      metadataError: row.metadata_error,
      bodyKind: row.fetched_body_kind,
      author: row.fetched_author_name,
      sourceCreatedAt: row.source_created_at,
      sourceUpdatedAt: row.source_updated_at,
      savedSnapshotNotice: hasSavedMetadata ? SAVED_SNAPSHOT_NOTICE : null,
      archivedAt: row.archived_at,
      contentFetchAllowed: row.content_fetch_allowed === true,
      rawBodyReturned: false,
      sharedTagBoundary: "local_personal_link_sync_only",
    },
  };
}

export async function getAiReceipt(args: Record<string, unknown>) {
  return {
    id: safeString(args.id),
    found: false,
    status: "not_synced",
    rawPromptReturned: false,
    rawBodyReturned: false,
  };
}
