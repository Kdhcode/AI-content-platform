// Run only against a local development DB: this collects news and confirms one issue.
const assert = require("node:assert/strict");
const { chromium } = require("playwright");

async function main() {
  const base = process.env.VERIFY_UI_URL || "http://localhost:3000";
  const username = process.env.ADMIN_USERNAME;
  const password = process.env.ADMIN_PASSWORD;
  assert(username && password, "Set ADMIN_USERNAME and ADMIN_PASSWORD");
  assert(["localhost", "127.0.0.1"].includes(new URL(base).hostname), "Use a local development server");
  const sourceName = process.env.VERIFY_SOURCE_NAME || "bbc-world";
  const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` };
  const json = async (path, method = "GET", body) => {
    const response = await fetch(base + path, {
      method, headers: { ...headers, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const envelope = await response.json();
    assert(response.ok && envelope.success, `${method} ${path}: ${envelope.error?.code || response.status}`);
    return envelope.data;
  };
  const waitFor = async (fn, label) => {
    const deadline = Date.now() + Number(process.env.VERIFY_TIMEOUT_SECONDS || 180) * 1000;
    while (Date.now() < deadline) {
      const value = await fn();
      if (value) return value;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error(`Timed out: ${label}`);
  };
  const browser = await chromium.launch({ headless: true, channel: process.env.VERIFY_BROWSER_CHANNEL || "chrome" });
  let restoreIssue;
  let issueId;
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(30000);
    const pageErrors = [];
    page.on("pageerror", error => pageErrors.push(error.message));
    await page.goto(base + "/login");
    await page.getByPlaceholder("아이디", { exact: true }).fill(username);
    await page.getByPlaceholder("비밀번호", { exact: true }).fill(password);
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await page.waitForURL(url => url.pathname === "/");
    await page.getByRole("link", { name: "뉴스 소스", exact: true }).click();
    const row = page.getByRole("row").filter({ hasText: sourceName });
    const collectResponse = page.waitForResponse(r => r.url().endsWith("/collect") && r.request().method() === "POST");
    await row.getByRole("button", { name: "지금 수집", exact: true }).click();
    const collection = (await (await collectResponse).json()).data;
    const completed = await waitFor(async () => {
      const job = await json(`/api/admin/jobs/${collection.id}`);
      assert(!["FAILED", "CANCELLED"].includes(job.status), `${job.errorCode}: ${job.errorMessage}`);
      return job.status === "SUCCESS" && job;
    }, "RSS collection");
    assert(completed.result.fetched > 0 && completed.result.errors === 0, "RSS returned articles without store failures");
    const sources = await json("/api/admin/sources");
    const source = sources.find(s => s.name === sourceName);
    const detail = await waitFor(async () => {
      const articles = await json(`/api/admin/articles?sourceId=${source.id}&size=100`);
      if (!articles.items.length) return false;
      for (const article of articles.items) {
        if (article.status === "DUPLICATE") continue;
        assert(article.status !== "FAILED", `Article ${article.id} failed`);
        const item = await json(`/api/admin/articles/${article.id}`);
        if (!item.hasEmbedding || !item.analysis || !item.issue) return false;
      }
      return json(`/api/admin/articles/${articles.items.find(a => a.status !== "DUPLICATE").id}`);
    }, "analysis, embedding and classification");
    if (process.env.VERIFY_REQUIRE_REAL_AI !== "false") {
      assert(detail.embeddingModel && !detail.embeddingModel.startsWith("stub"),
        "Real AI required. Stub validation must explicitly set VERIFY_REQUIRE_REAL_AI=false");
    }
    await page.goto(base + `/articles/${detail.id}`);
    await page.getByRole("heading", { name: `기사 #${detail.id}`, exact: true }).waitFor();
    await page.getByText(detail.title, { exact: true }).first().waitFor();
    issueId = detail.issue.issueId;
    const original = await json(`/api/admin/issues/${issueId}`);
    assert(["ACTIVE", "REVIEW"].includes(original.status), "An open issue is required");
    restoreIssue = original.status;
    await json(`/api/admin/issues/${issueId}`, "PATCH", { status: "REVIEW", reason: "Local browser flow verification" });
    await page.goto(base + `/issues/${issueId}`);
    await page.getByRole("button", { name: "검수 확정(ACTIVE)", exact: true }).click();
    await page.getByText("상태를 ACTIVE(으)로 변경했습니다.", { exact: true }).waitFor();
    const confirmed = await json(`/api/admin/issues/${issueId}`);
    assert.equal(confirmed.status, "ACTIVE");
    const finalArticle = await json(`/api/admin/articles/${detail.id}`);
    assert.equal(finalArticle.classificationStatus, "CLASSIFIED");
    assert.equal(finalArticle.issue.issueStatus, "ACTIVE");
    // Leave the chosen issue confirmed. Restore only if verification aborts before confirmation.
    restoreIssue = undefined;
    assert.deepEqual(pageErrors, [], "No browser runtime errors");
    console.log(JSON.stringify({ result: "PASS", source: sourceName, collectionJobId: collection.id,
      fetched: completed.result.fetched, created: completed.result.created, articleId: detail.id,
      issueId, embeddingModel: detail.embeddingModel, issueStatus: confirmed.status,
      realAiRequired: process.env.VERIFY_REQUIRE_REAL_AI !== "false", browserErrors: pageErrors.length }, null, 2));
  } finally {
    if (restoreIssue && issueId) {
      await json(`/api/admin/issues/${issueId}`, "PATCH", { status: restoreIssue, reason: "Restore after flow verification failure" });
    }
    await browser.close();
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
