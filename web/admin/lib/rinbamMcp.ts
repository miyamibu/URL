import crypto from "node:crypto";
import { optionalEnv, requireEnv } from "./env";
import { createMcpUserSupabaseClient, createServiceSupabaseClient } from "./supabase";

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
const MCP_RATE_LIMIT_WINDOW_SECONDS = 60;
const MCP_RATE_LIMIT_MAX_REQUESTS = 60;

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
        includeSharedTags: { type: "boolean", default: false },
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

const allowedToolArgumentFields: Record<string, readonly string[]> = {
  search: ["query", "limit", "includeSharedTags"],
  fetch: ["id"],
  "rinbam.list_tags": [],
  "rinbam.get_ai_receipt": ["id"],
  "rinbam.list_recent_saved_links": ["limit", "includeSharedTags"],
};

export function validateRinbamMcpToolArguments(toolName: string, args: Record<string, unknown>) {
  const allowed = allowedToolArgumentFields[toolName];
  if (!allowed) throw new RinbamMcpToolNotFoundError(toolName);
  const unknownField = Object.keys(args).find((key) => !allowed.includes(key));
  if (unknownField) throw new RinbamMcpInputError(`unknown_input_field:${unknownField}`);

  if (toolName === "search" && typeof args.query !== "string") {
    throw new RinbamMcpInputError("query_must_be_string");
  }
  if (typeof args.query === "string" && args.query.length > 500) {
    throw new RinbamMcpInputError("query_too_long");
  }
  if (args.limit !== undefined && (typeof args.limit !== "number" || !Number.isInteger(args.limit) || args.limit < 1 || args.limit > 20)) {
    throw new RinbamMcpInputError("limit_must_be_integer_1_to_20");
  }
  for (const key of ["includeSharedTags"]) {
    if (args[key] !== undefined && typeof args[key] !== "boolean") {
      throw new RinbamMcpInputError(`${key}_must_be_boolean`);
    }
  }
  if ((toolName === "fetch" || toolName === "rinbam.get_ai_receipt") && typeof args.id !== "string") {
    throw new RinbamMcpInputError("id_must_be_string");
  }
  if (toolName === "fetch" && typeof args.id === "string" && args.id.length < 16) {
    throw new RinbamMcpInputError("id_too_short");
  }
  if (toolName === "rinbam.get_ai_receipt" && typeof args.id === "string" && args.id.trim().length === 0) {
    throw new RinbamMcpInputError("id_must_not_be_empty");
  }
}

export async function callRinbamMcpTool(
  ctx: RinbamMcpContext,
  toolName: string,
  args: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  validateRinbamMcpToolArguments(toolName, args);
  let payload: Record<string, unknown>;
  switch (toolName) {
    case "search":
      payload = await searchRinbamLinks(ctx, args);
      break;
    case "fetch":
      payload = await fetchRinbamLink(ctx, args);
      break;
    case "rinbam.list_tags":
      payload = await listRinbamTags(ctx);
      break;
    case "rinbam.get_ai_receipt":
      payload = await getAiReceipt(args);
      break;
    case "rinbam.list_recent_saved_links":
      payload = await listRecentSavedLinks(ctx, args);
      break;
    default:
      throw new RinbamMcpToolNotFoundError(toolName);
  }
  return sanitizeRinbamMcpOutput(payload) as Record<string, unknown>;
}

export async function handleRinbamMcpJsonRpcRequest(
  request: RinbamMcpJsonRpcRequest,
  ctx: RinbamMcpContext,
): Promise<RinbamMcpJsonRpcResponse | null> {
  const parsed = parseRinbamMcpJsonRpcRequest(request);
  if (!("id" in parsed) || parsed.id === undefined) {
    if (parsed.method.startsWith("notifications/")) {
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
  public_safe_id: string;
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
  supabase: ReturnType<typeof createMcpUserSupabaseClient>;
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
  const userSupabase = createMcpUserSupabaseClient(token);
  return {
    userId: data.user.id,
    email: data.user.email ?? null,
    token,
    supabase: userSupabase,
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
  constructor(message = "rate_limited", public readonly retryAfterSeconds = 1) {
    super(message);
    this.name = "RinbamMcpRateLimitError";
  }
}

export class RinbamMcpRateLimitUnavailableError extends Error {
  constructor() {
    super("rate_limit_unavailable");
    this.name = "RinbamMcpRateLimitUnavailableError";
  }
}

export async function checkRinbamMcpRateLimit(ctx: RinbamMcpContext) {
  const { data, error } = await ctx.supabase.rpc("consume_rinbam_mcp_rate_limit", {
    p_window_seconds: MCP_RATE_LIMIT_WINDOW_SECONDS,
    p_max_requests: MCP_RATE_LIMIT_MAX_REQUESTS,
  });
  if (error) throw new RinbamMcpRateLimitUnavailableError();
  const row = Array.isArray(data) ? data[0] : data;
  if (!isRecord(row) || typeof row.allowed !== "boolean") {
    throw new RinbamMcpRateLimitUnavailableError();
  }
  if (!row.allowed) {
    const retryAfter = typeof row.retry_after_seconds === "number" && Number.isFinite(row.retry_after_seconds)
      ? Math.max(1, Math.trunc(row.retry_after_seconds))
      : 1;
    throw new RinbamMcpRateLimitError("rate_limited", retryAfter);
  }
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
  return typeof value === "number" && Number.isInteger(value) ? value : fallback;
}

function safeString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function safeText(value: string | null | undefined, maxLength = 1200): string {
  const trimmed = (value ?? "")
    .replace(/\u0000/g, "")
    .replace(/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, "[redacted:email]")
    .replace(/\b(?:bearer\s+)?(?:refresh_token|access_token|service_role|sb_secret|token|secret|password|api[_-]?key)\s*[:=]\s*["']?[A-Za-z0-9._~+/=-]{8,}/gi, "[redacted:secret]")
    .replace(/\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9_-]{16,}|xox[baprs]-[A-Za-z0-9-]{16,}|AIza[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16})\b/g, "[redacted:secret]")
    .replace(/\beyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g, "[redacted:jwt]")
    .replace(/(?:\/Users\/|\/home\/|[A-Za-z]:\\)[^\s]+/g, "[redacted:path]")
    .trim();
  return trimmed.length > maxLength ? `${trimmed.slice(0, maxLength - 1)}…` : trimmed;
}

const sensitiveOutputKey = /(?:fetched[_-]?body|raw[_-]?(?:body|prompt)|access[_-]?token|refresh[_-]?token|service[_-]?role|secret|password)/i;

export function sanitizeRinbamMcpOutput(value: unknown): unknown {
  if (typeof value === "string") return safeText(value);
  if (Array.isArray(value)) return value.map((item) => sanitizeRinbamMcpOutput(item));
  if (!isRecord(value)) return value;
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !sensitiveOutputKey.test(key))
      .map(([key, item]) => [key, sanitizeRinbamMcpOutput(item)]),
  );
}

function rejectSharedTagOptIn(args: Record<string, unknown>) {
  if (args.includeSharedTags === true) {
    throw new RinbamMcpInputError("include_shared_tags_requires_explicit_scope");
  }
}

function rpcSearchResult(row: Record<string, unknown>) {
  const tags = Array.isArray(row.tag_names)
    ? row.tag_names.filter((tag): tag is string => typeof tag === "string").map((tag) => safeText(tag, 80)).sort()
    : [];
  return {
    id: safeText(row.public_safe_id as string | null, 64),
    title: safeText((row.title as string | null) || "保存したリンク"),
    url: safeText((row.url as string | null) || "", 2000),
    snippet: safeText((row.snippet as string | null) || ""),
    metadata: sanitizeRinbamMcpOutput(isRecord(row.metadata) ? row.metadata : {}),
    tags,
    matchReason: "personal_saved_links",
    aiEligible: true,
    sharedTagBoundary: "local_personal_link_sync_only",
    rawBodyReturned: false,
  };
}

export async function searchRinbamLinks(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  rejectSharedTagOptIn(args);
  const { data, error } = await ctx.supabase.rpc("mcp_search_active_personal_saved_links", {
    p_query: safeString(args.query),
    p_result_limit: clampLimit(args.limit),
  });
  if (error) throw error;
  const rows = Array.isArray(data) ? data.filter(isRecord) : [];
  return { results: rows.map(rpcSearchResult), includeSharedTags: false, rawBodyReturned: false };
}

export async function listRecentSavedLinks(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  rejectSharedTagOptIn(args);
  return searchRinbamLinks(ctx, { query: "", limit: args.limit });
}

export async function listRinbamTags(ctx: RinbamMcpContext) {
  const { data, error } = await ctx.supabase.rpc("mcp_list_active_personal_link_tags");
  if (error) throw error;
  const tags = (Array.isArray(data) ? data : [])
    .filter(isRecord)
    .map((tag) => ({
      id: publicSafeId(ctx.userId, safeString(tag.tag_id)),
      name: safeText(safeString(tag.name), 80),
      sharedTagBoundary: "local_only",
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
  return { tags, rawBodyReturned: false };
}

export async function fetchRinbamLink(ctx: RinbamMcpContext, args: Record<string, unknown>) {
  const id = safeText(safeString(args.id), 64);
  const { data: row, error } = await ctx.supabase
    .from("personal_saved_links")
    .select("id,public_safe_id,effective_title,open_url,normalized_url,normalized_host,memo,body_summary,description,fetched_author_name,fetched_body_kind,service_type,record_state,metadata_state,metadata_error,source_created_at,source_updated_at,content_fetch_allowed")
    .eq("user_id", ctx.userId)
    .eq("public_safe_id", id)
    .eq("record_state", "ACTIVE")
    .neq("record_state", "PENDING_DELETE")
    .is("deleted_at", null)
    .is("disabled_at", null)
    .maybeSingle();
  if (error) throw error;
  if (!row) return { id, found: false };
  const link = row as unknown as PersonalSavedLinkRow;
  const { data: refs, error: refError } = await ctx.supabase
    .from("personal_saved_link_tag_refs")
    .select("tag_id")
    .eq("user_id", ctx.userId)
    .eq("link_id", link.id)
    .is("deleted_at", null);
  if (refError) throw refError;
  const tagIds = (refs ?? []).map((ref) => (ref as { tag_id: string }).tag_id);
  const { data: tagRows, error: tagError } = tagIds.length === 0
    ? { data: [], error: null }
    : await ctx.supabase.from("personal_saved_link_tags").select("id,name").eq("user_id", ctx.userId).in("id", tagIds).is("deleted_at", null);
  if (tagError) throw tagError;
  const tags = (tagRows ?? []).map((tag) => safeText((tag as TagRow).name, 80)).sort();
  const title = safeText(link.effective_title || link.normalized_host || "保存したリンク");
  const url = safeText(link.open_url || link.normalized_url || "", 2000);
  const hasSavedMetadata = Boolean(link.source_updated_at || link.body_summary || link.description || link.fetched_author_name || link.fetched_body_kind);
  const text = [
    `# ${title}`,
    `URL: ${url}`,
    `Service: ${safeText(link.service_type)}`,
    "State: ACTIVE",
    tags.length > 0 ? `Tags: ${tags.join(", ")}` : "Tags: none",
    link.body_summary ? `Summary: ${safeText(link.body_summary)}` : null,
    link.description ? `Description: ${safeText(link.description)}` : null,
    link.memo ? `Memo excerpt: ${safeText(link.memo)}` : null,
    hasSavedMetadata ? `Saved snapshot notice: ${SAVED_SNAPSHOT_NOTICE}` : null,
  ].filter(Boolean).join("\n");
  return {
    id,
    found: true,
    title,
    text,
    url,
    metadata: {
      recordState: "ACTIVE",
      metadataState: link.metadata_state,
      metadataError: link.metadata_error,
      bodyKind: link.fetched_body_kind,
      author: link.fetched_author_name,
      sourceCreatedAt: link.source_created_at,
      sourceUpdatedAt: link.source_updated_at,
      savedSnapshotNotice: hasSavedMetadata ? SAVED_SNAPSHOT_NOTICE : null,
      contentFetchAllowed: link.content_fetch_allowed === true,
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
