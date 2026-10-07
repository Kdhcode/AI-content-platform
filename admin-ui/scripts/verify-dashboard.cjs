const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

async function main() {
  const base = process.env.VERIFY_UI_URL || "http://localhost:3000";
  assert(["localhost", "127.0.0.1"].includes(new URL(base).hostname), "Use a local development server");
  const username = process.env.ADMIN_USERNAME;
  const password = process.env.ADMIN_PASSWORD;
  assert(username && password, "Set ADMIN_USERNAME and ADMIN_PASSWORD");
  const output = path.resolve(__dirname, "../../.local/screenshots");
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: process.env.VERIFY_BROWSER_CHANNEL || "chrome", headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 1 });
    const errors = [];
    const apiFailures = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("response", response => {
      if (response.url().includes("/api/admin/") && response.status() >= 400) apiFailures.push(response.status() + " " + response.url());
    });
    await page.goto(base + "/login");
    await page.screenshot({ path: path.join(output, "newsroom-login.png"), fullPage: true });
    await page.getByLabel("아이디", { exact: true }).fill(username);
    await page.getByLabel("비밀번호", { exact: true }).fill(password);
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await page.waitForURL(url => url.pathname === "/");
    await page.getByText("DB 연결됨", { exact: true }).waitFor();
    const response = await page.request.get(base + "/api/admin/dashboard", {
      headers: { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` },
    });
    assert.equal(response.status(), 200);
    const data = (await response.json()).data;
    const values = await page.locator(".stat-value").allTextContents();
    assert.deepEqual(values, [data.counts.articles.toLocaleString() + "건", data.counts.issues.toLocaleString() + "개", data.counts.reviewIssues.toLocaleString() + "건", data.counts.successfulJobs.toLocaleString() + "건"]);
    assert.equal(await page.locator(".issue-feed-item").count(), Math.min(4, data.counts.issues));
    const sourceResponse = await page.request.get(base + "/api/admin/sources", {
      headers: { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` },
    });
    const sources = (await sourceResponse.json()).data;
    assert.equal(await page.locator(".source-summary").count(), sources.length);
    assert.equal(await page.locator(".pipeline-step").count(), 4);
    await page.screenshot({ path: path.join(output, "newsroom-dashboard.png"), fullPage: true });
    await page.screenshot({ path: path.join(output, "newsroom-dashboard-preview.png"), fullPage: false });
    await page.getByRole("link", { name: "검수 대기 목록 열기" }).click();
    await page.waitForURL("**/issues?status=REVIEW");
    await page.waitForFunction(() => document.querySelector("select")?.value === "REVIEW");
    for (const [route, heading] of [["/issues", "이슈 관리"], ["/articles", "기사"], ["/sources", "뉴스 소스"], ["/jobs", "작업"]]) {
      await page.goto(base + route);
      await page.getByRole("heading", { name: heading, exact: true }).waitFor();
      await page.locator("tbody tr").first().waitFor();
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base);
    await page.getByText("DB 연결됨", { exact: true }).waitFor();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, "No horizontal overflow on mobile dashboard");
    await page.screenshot({ path: path.join(output, "newsroom-mobile.png"), fullPage: true });
    assert.deepEqual(errors, []);
    assert.deepEqual(apiFailures, []);
    console.log(JSON.stringify({ result: "PASS", desktop: "1440px", mobile: "390px", stats: data.counts,
      database: data.system, browserErrors: errors.length, apiFailures: apiFailures.length, screenshots: output }, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
