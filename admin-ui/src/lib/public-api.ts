export type PublicIssue = {
  id: number; title: string; summary: string | null; category: string | null; status: string;
  updatedAt: string | null; articleCount: number; publisherCount: number;
};
export type PublicPage = { items: PublicIssue[]; totalElements: number; totalPages: number; page: number };
export type PublicDetail = { issue: PublicIssue; articles: {
  id: number; title: string; publisherName: string; publishedAt: string | null; url: string;
}[] };

export async function publicApi<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`/api/public/${path}`, { signal, credentials: "omit" });
  const body = await response.json();
  if (!response.ok || !body.success) throw new Error(response.status === 404
    ? "공개된 이슈를 찾을 수 없습니다." : "뉴스를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
  return body.data;
}
export const categoryName = (value: string | null) => ({ POLITICS: "정치", ECONOMY: "경제", SOCIETY: "사회",
  WORLD: "세계", INTERNATIONAL: "세계", TECHNOLOGY: "기술", SCIENCE: "과학", CULTURE: "문화", SPORTS: "스포츠", OTHER: "일반" }[value || "OTHER"] || value);
export const newsDate = (date: string | null) => date ? new Date(date).toLocaleDateString("ko-KR", { month: "long", day: "numeric" }) : "날짜 미상";
export function safeArticleUrl(value: string): string | null {
  try { const url = new URL(value); return ["http:", "https:"].includes(url.protocol) ? url.href : null; }
  catch { return null; }
}
