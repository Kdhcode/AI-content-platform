const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

// Read-only check of the AI usage page at 1440px and 390px against the API it renders (changes no data).
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
    await page.getByLabel("아이디", { exact: true }).fill(username);
    await page.getByLabel("비밀번호", { exact: true }).fill(password);
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await page.waitForURL(url => url.pathname === "/");

    await page.getByRole("link", { name: "AI 사용량" }).click();
    await page.waitForURL("**/ai-usage");
    await page.getByRole("heading", { name: "AI 사용량", exact: true }).waitFor();
    await page.locator("tbody tr").first().waitFor();

    const headers = { Authorization: `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}` };
    const usage = async days => {
      const response = await page.request.get(`${base}/api/admin/ai-usage?days=${days}`, { headers });
      assert.equal(response.status(), 200);
      return (await response.json()).data;
    };
    const cost = totals => (totals.costUsd === null ? "단가 미설정" : "$" + totals.costUsd.toFixed(6));
    const check = async days => {
      const data = await usage(days);
      const rows = Math.max(1, data.rows.length);   // an empty period renders one "no calls" row
      await page.waitForFunction(n => document.querySelectorAll("tbody tr").length === n, rows);
      const values = await page.locator(".stat-value").allTextContents();
      assert.deepEqual(values, [data.totals.calls.toLocaleString("ko-KR") + "건", data.totals.inputTokens.toLocaleString("ko-KR"),
        data.totals.outputTokens.toLocaleString("ko-KR"), cost(data.totals)]);
      const unpricedRows = data.rows.filter(row => row.costUsd === null).length;
      assert.equal(await page.locator("tbody td:last-child", { hasText: /^단가 미설정$/ }).count(), unpricedRows);
      return data;
    };

    const week = await check(7);
    await page.screenshot({ path: path.join(output, "ai-usage-desktop.png"), fullPage: true });
    for (const days of [1, 30]) {
      await page.getByLabel("기간").selectOption(String(days));
      await check(days);
    }
    await page.getByLabel("기간").selectOption("7");
    await check(7);

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(base + "/ai-usage");
    await page.getByRole("heading", { name: "AI 사용량", exact: true }).waitFor();
    await page.locator("tbody tr").first().waitFor();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    assert.equal(overflow, false, "No horizontal overflow on the mobile AI usage page");
    await page.screenshot({ path: path.join(output, "ai-usage-mobile.png"), fullPage: true });
    assert.deepEqual(errors, []);
    assert.deepEqual(apiFailures, []);
    console.log(JSON.stringify({ result: "PASS", desktop: "1440px", mobile: "390px", rows: week.rows.length, totals: week.totals,
      browserErrors: errors.length, apiFailures: apiFailures.length, screenshots: output }, null, 2));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
