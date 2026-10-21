// Typed client for the admin API. Credentials (HTTP Basic) live in sessionStorage only: they vanish when the tab
// closes. This is the Phase 1 minimum; the final login mechanism is an OPEN ITEM.

export type ApiError = { code: string; message: string; details?: unknown };
type Envelope<T> = { success: boolean; data: T | null; error: ApiError | null };

export class ApiFailure extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) {
    super(message);
  }
}

const KEY = "admin-basic-auth";

export function setCredentials(username: string, password: string) {
  sessionStorage.setItem(KEY, btoa(unescape(encodeURIComponent(`${username.trim()}:${password}`))));
}
export function clearCredentials() {
  sessionStorage.removeItem(KEY);
}
/** True only when the person signed in with an account (not merely local-verify mode). */
export function hasLogin(): boolean {
  return typeof window !== "undefined" && !!sessionStorage.getItem(KEY);
}
export function hasCredentials(): boolean {
  return typeof window !== "undefined" && (!!sessionStorage.getItem(KEY) || bypassKnown);
}

/** Local screen-check mode (backend profile local-verify, loopback only, no paid AI): the admin API needs no login. */
export type LocalVerifyMode = { loginBypass: boolean; dataLabel: string | null; aiProvider: string | null };
const MODE_OFF: LocalVerifyMode = { loginBypass: false, dataLabel: null, aiProvider: null };
let modeRequest: Promise<LocalVerifyMode> | null = null;
let bypassKnown = false;

/** Asked once per page load; any failure counts as "off", so the normal login stays in place. */
export function localVerifyMode(): Promise<LocalVerifyMode> {
  if (!modeRequest) {
    modeRequest = fetch("/api/local-verify/mode")
      .then((r) => r.json() as Promise<Envelope<LocalVerifyMode>>)
      .then((e) => (e?.success && e.data ? e.data : MODE_OFF))
      .catch(() => MODE_OFF)
      .then((m) => { bypassKnown = m.loginBypass; return m; });
  }
  return modeRequest;
}

export async function api<T>(path: string, init: { method?: string; body?: unknown; headers?: Record<string, string> } = {}): Promise<T> {
  const token = sessionStorage.getItem(KEY);
  if (!token && !(await localVerifyMode()).loginBypass) {
    if (window.location.pathname !== "/login") window.location.replace("/login");
    throw new ApiFailure(401, "UNAUTHORIZED", "로그인이 필요합니다. 관리자 계정으로 로그인해 주세요.");
  }
  const res = await fetch(path, {
    method: init.method ?? "GET",
    headers: {
      ...(token ? { Authorization: `Basic ${token}` } : {}),
      ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(init.headers ?? {}),
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  let envelope: Envelope<T> | null = null;
  try {
    envelope = (await res.json()) as Envelope<T>;
  } catch {
    // non-JSON body (proxy error page etc.)
  }
  if (res.status === 401 && sessionStorage.getItem(KEY) === token) {
    clearCredentials();
    if (window.location.pathname !== "/login") window.location.replace("/login");
  }
  if (!res.ok || !envelope || !envelope.success) {
    throw new ApiFailure(res.status, envelope?.error?.code ?? "HTTP_" + res.status, envelope?.error?.message ?? res.statusText,
      envelope?.error?.details);
  }
  return envelope.data as T;
}

export type Page<T> = { items: T[]; totalElements: number; totalPages: number; page: number; size: number };

export type ArticleListItem = {
  id: number; title: string; publisherName: string; publishedAt: string | null; collectedAt: string;
  status: string; classificationStatus: string; sourceId: number; issueId: number | null; issueStatus: string | null;
};
export type ClassificationRun = {
  id: number; createdAt: string; decision: string; rawDecision: string | null; method: string; confidence: number | null;
  reason: string | null; matchedIssueId: number | null; appliedIssueId: number | null; applied: boolean;
  candidates: { issueId: number; title: string; similarity: number; articleCount: number }[] | null;
  promptVersion: string | null; model: string | null;
};
export type ArticleDetail = {
  id: number; sourceName: string; title: string; originalUrl: string; publisherName: string; author: string | null;
  category: string | null; publishedAt: string | null; collectedAt: string; status: string;
  duplicateOfArticleId: number | null; classificationStatus: string; analysisText: string | null;
  analysis: { summary: string; keyFacts: string[]; entities: { name: string; type: string }[]; category: string;
    eventType: string; multiEvent?: boolean; confidence: number } | null;
  analysisError: string | null; hasEmbedding: boolean;
  issue: { issueId: number; issueTitle: string; issueStatus: string; method: string; llmDecision: string | null;
    llmConfidence: number | null; llmReason: string | null; similarity: number | null; manuallyCorrected: boolean } | null;
  classificationRuns: ClassificationRun[];
};
export type IssueListItem = {
  id: number; title: string; summary: string | null; category: string | null; status: string; articleCount: number;
  publisherCount: number; firstPublishedAt: string | null; lastUpdatedAt: string | null; mergedIntoIssueId: number | null;
};
export type IssueDetail = IssueListItem & {
  titleSource: string; summarySource: string; keyFacts: string[]; entities: { name: string; type: string }[]; mergedFromIssueIds: number[];
  articles: { articleId: number; title: string; publisherName: string; publishedAt: string | null;
    classificationMethod: string; llmDecision: string | null; llmConfidence: number | null; llmReason: string | null;
    similarity: number | null; manuallyCorrected: boolean; runMethod: string | null; runDecision: string | null;
    heldWithPendingIssue: boolean;
    candidates: { issueId: number; status?: string; title: string; similarity: number; articleCount: number }[] | null }[];
};
export type Job = {
  id: number; jobType: string; status: string; payload: Record<string, unknown>; result: Record<string, unknown> | null;
  retryCount: number; maxRetries: number; deferCount: number; errorCode: string | null; errorMessage: string | null; requestedAt: string;
  finishedAt: string | null;
};

export type AiUsageRow = {
  day: string; callType: string; provider: string | null; model: string | null; calls: number; failures: number;
  inputTokens: number; outputTokens: number; costUsd: number | null; unpricedCalls: number;
};
/** app.ai.limits and today's usage against them. A limit that is off is null. */
export type AiLimits = {
  dailyBudgetUsd: number | null; dailyMaxCalls: number | null; maxCallsPerMinute: number | null; timeZone: string;
  callsToday: number; costToday: number; callsLastMinute: number; blockedBy: string | null;
};
export type AiUsage = {
  days: number; rows: AiUsageRow[];
  totals: { calls: number; failures: number; inputTokens: number; outputTokens: number; costUsd: number | null;
    unpricedCalls: number };
  limits: AiLimits;
};

export type NewsSource = {
  id: number; name: string; type: string; baseUrl: string | null; enabled: boolean; status: string;
  collectionIntervalSeconds: number; lastAttemptAt: string | null; lastSuccessAt: string | null;
  failureCount: number; lastError: string | null;
};

export type Dashboard = {
  counts: { articles: number; issues: number; reviewIssues: number; enabledSources: number;
    successfulJobs: number; failedJobs: number; waitingJobs: number; auditEvents: number };
  pipeline: { jobType: string; total: number; success: number; failed: number; pending: number; running: number }[];
  system: { database: string; postgresVersion: string; port: number; vectorVersion: string | null;
    schemaVersion: string | null; aiProvider: string; schedulerEnabled: boolean };
  checkedAt: string;
};

/** The signed-in account (GET /api/admin/me). Only for showing the right buttons; the server checks every action. */
export type Me = { username: string; roles: string[] };

let meCache: { token: string | null; request: Promise<Me> } | null = null;
/** Asked once per sign-in (a new login asks again). */
export function currentUser(): Promise<Me> {
  const token = typeof window === "undefined" ? null : sessionStorage.getItem(KEY);
  if (!meCache || meCache.token !== token) {
    const request = api<Me>("/api/admin/me");
    meCache = { token, request };
    request.catch(() => { if (meCache?.request === request) meCache = null; });
  }
  return meCache.request;
}

export type ContentBody = { schemaVersion: 1; sections: { id: string; heading: string; paragraphs: { id: string; text: string }[] }[] };
export type OriginChange = {
  unacknowledged: boolean; issueChangeSeq: number; acknowledgedSeq: number; issueChangedAt: string | null;
  acknowledgedAt: string | null; issueStatus: string; notice: "MERGED" | "EXCLUDED" | null; mergedIntoIssueId: number | null;
};
export type ContentSource = {
  id: number; kind: "ISSUE" | "NEWS_ARTICLE" | "EXTERNAL_URL" | "MANUAL"; issueId: number | null; newsArticleId: number | null;
  externalUrl: string | null; label: string | null; role: "PRIMARY" | "SUPPORTING" | "REFERENCE"; checkedAt: string | null;
};
export type ContentLink = {
  id: number; linkType: string; title: string; url: string; siteVisible: boolean; blogVisible: boolean;
  displayPosition: "BODY_TOP" | "BODY_BOTTOM" | "SOURCES"; sortOrder: number; createdAt: string | null; updatedAt: string | null;
};
export type ContentDetail = {
  id: number; contentType: "NEWS" | "LIFE"; status: string; title: string; body: ContentBody; version: number;
  originIssue: { id: number; title: string; status: string; mergedIntoIssueId: number | null };
  originCheckedAt: string | null; originChange: OriginChange; sources: ContentSource[]; links: ContentLink[];
  statusReason: string | null; createdBy: string; createdAt: string; updatedBy: string; updatedAt: string;
  submittedAt: string | null; approvedBy: string | null; approvedAt: string | null;
};
export type ContentListItem = {
  id: number; contentType: "NEWS" | "LIFE"; status: string; title: string; version: number; originIssueId: number;
  originIssueTitle: string; originIssueStatus: string; originChange: OriginChange; updatedBy: string; updatedAt: string;
};
/** Response of every content change except the status actions (those return the detail itself). */
export type ContentChangeResult = { content: ContentDetail; changed: boolean; statusChanged: boolean; itemId: number | null };

export function qs(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const fmt = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString("ko-KR") : "-");
