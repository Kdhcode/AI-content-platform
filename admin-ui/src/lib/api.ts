// Typed client for the admin API. Credentials (HTTP Basic) live in sessionStorage only: they vanish when the tab
// closes. This is the Phase 1 minimum; the final login mechanism is an OPEN ITEM.

export type ApiError = { code: string; message: string; details?: unknown };
type Envelope<T> = { success: boolean; data: T | null; error: ApiError | null };

export class ApiFailure extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

const KEY = "admin-basic-auth";

export function setCredentials(username: string, password: string) {
  sessionStorage.setItem(KEY, btoa(unescape(encodeURIComponent(`${username}:${password}`))));
}
export function clearCredentials() {
  sessionStorage.removeItem(KEY);
}
export function hasCredentials(): boolean {
  return typeof window !== "undefined" && !!sessionStorage.getItem(KEY);
}

export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const token = sessionStorage.getItem(KEY);
  const res = await fetch(path, {
    method: init.method ?? "GET",
    headers: {
      ...(token ? { Authorization: `Basic ${token}` } : {}),
      ...(init.body !== undefined ? { "Content-Type": "application/json" } : {}),
    },
    body: init.body !== undefined ? JSON.stringify(init.body) : undefined,
  });
  let envelope: Envelope<T> | null = null;
  try {
    envelope = (await res.json()) as Envelope<T>;
  } catch {
    // non-JSON body (proxy error page etc.)
  }
  if (res.status === 401) {
    clearCredentials();
    if (typeof window !== "undefined" && window.location.pathname !== "/login") window.location.href = "/login";
  }
  if (!res.ok || !envelope || !envelope.success) {
    throw new ApiFailure(res.status, envelope?.error?.code ?? "HTTP_" + res.status, envelope?.error?.message ?? res.statusText);
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
  summarySource: string; keyFacts: string[]; entities: { name: string; type: string }[]; mergedFromIssueIds: number[];
  articles: { articleId: number; title: string; publisherName: string; publishedAt: string | null;
    classificationMethod: string; llmDecision: string | null; llmConfidence: number | null; llmReason: string | null;
    similarity: number | null; manuallyCorrected: boolean }[];
};
export type Job = {
  id: number; jobType: string; status: string; payload: Record<string, unknown>; result: Record<string, unknown> | null;
  retryCount: number; maxRetries: number; errorCode: string | null; errorMessage: string | null; requestedAt: string;
  finishedAt: string | null;
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

export function qs(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : "";
}

export const fmt = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleString("ko-KR") : "-");
