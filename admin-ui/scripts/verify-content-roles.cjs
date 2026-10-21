// Content screens per role in normal login mode (no local-verify): what an OPERATOR can do, that approve / restore are
// limited to SYSTEM_ADMIN in the screen AND refused (403) when sent directly through the Next proxy without changing the
// content, and that logging out / switching accounts in the same tab leaves no rights or screen state behind.
// Creates two contents on the first ACTIVE issue of the connected database and approves / archives one, so it runs
// only against a verify environment: there is no default address, the daily (3000) and dev (3001) UI ports are refused,
// and the person running it must confirm the verify DB by repeating the port (scripts/lib/write-target.cjs, checked
// before the browser starts or any request is sent). That is a guard against mistakes, not proof of the database.
//   VERIFY_UI_URL=http://127.0.0.1:13098 VERIFY_CONFIRM_VERIFY_DB=13098 \
//   OPERATOR_USERNAME=... OPERATOR_PASSWORD=... ADMIN_USERNAME=... ADMIN_PASSWORD=... npm run verify:content-roles
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { checkWriteTarget } = require("./lib/write-target.cjs");

const REVIEW_FIELDS = ["status", "version", "submittedAt", "approvedBy", "approvedAt", "statusReason", "title"];

async function main() {
  const { base } = checkWriteTarget(process.env, ["OPERATOR_USERNAME", "OPERATOR_PASSWORD", "ADMIN_USERNAME", "ADMIN_PASSWORD"],
    "npm run verify:content-roles");
  const operator = { user: process.env.OPERATOR_USERNAME, pw: process.env.OPERATOR_PASSWORD, role: "OPERATOR" };
  const admin = { user: process.env.ADMIN_USERNAME, pw: process.env.ADMIN_PASSWORD, role: "SYSTEM_ADMIN" };
  const { chromium } = require("playwright");
  const output = path.resolve(__dirname, "../../.local/screenshots");
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: process.env.VERIFY_BROWSER_CHANNEL || "chrome", headless: true });
  const steps = [];
  const step = (name) => { steps.push(name); console.log("PASS " + name); };
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));

    // the logged-in account's own request through the Next proxy (same credentials the screen uses)
    const call = (method, url, body) => page.evaluate(async ({ method, url, body }) => {
      const token = sessionStorage.getItem("admin-basic-auth");
      const r = await fetch(url, { method, headers: { ...(token ? { Authorization: "Basic " + token } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
        body: body ? JSON.stringify(body) : undefined });
      let json = null; try { json = await r.json(); } catch { /* not JSON */ }
      return { status: r.status, data: json && json.data, error: json && json.error };
    }, { method, url, body });
    const detail = async (id) => { const r = await call("GET", `/api/admin/contents/${id}`); assert.equal(r.status, 200); return r.data; };
    const pick = (c) => Object.fromEntries(REVIEW_FIELDS.map((f) => [f, c[f] ?? null]));
    const waitFor = async (id, test, what) => {
      for (let i = 0; i < 40; i++) { const c = await detail(id); if (test(c)) return c; await page.waitForTimeout(250); }
      throw new Error(`content ${id}: ${what} did not happen`);
    };

    async function login(who) {
      await page.goto(base + "/login");
      await page.getByLabel("아이디", { exact: true }).fill(who.user);
      await page.getByLabel("비밀번호", { exact: true }).fill(who.pw);
      await page.getByRole("button", { name: "로그인", exact: true }).click();
      await page.waitForURL((u) => u.pathname === "/");
      await page.locator(".sidebar-account", { hasText: who.user }).filter({ hasText: who.role }).waitFor();
      const me = await call("GET", "/api/admin/me");
      assert.equal(me.status, 200);
      assert.deepEqual([me.data.username, me.data.roles.join(",")], [who.user, who.role]);
    }
    async function logout() {
      await page.getByRole("button", { name: "로그아웃" }).click();
      await page.waitForURL((u) => u.pathname === "/login");
      assert.equal(await page.evaluate(() => sessionStorage.getItem("admin-basic-auth")), null);
      await page.goto(base + "/contents");                     // no credentials left: the protected page sends to login
      await page.waitForURL((u) => u.pathname === "/login");
      assert.equal((await call("GET", "/api/admin/me")).status, 401);
    }
    async function act(label, reason) {
      await page.getByRole("button", { name: label, exact: true }).click();
      if (reason) await page.locator("#action-reason").fill(reason);
      await page.getByRole("button", { name: `${label} 실행`, exact: true }).click();
    }
    // the screen shows the SYSTEM_ADMIN-only action disabled; the direct request is refused and changes nothing
    async function deniedForOperator(id, action, label, body) {
      await page.getByRole("button", { name: `${label} (SYSTEM_ADMIN)`, exact: true }).waitFor();
      assert.equal(await page.getByRole("button", { name: `${label} (SYSTEM_ADMIN)`, exact: true }).isDisabled(), true,
        `the "${label}" button must be disabled for an OPERATOR`);
      assert.equal(await page.getByRole("button", { name: label, exact: true }).count(), 0, `an enabled "${label}" button is shown to an OPERATOR`);
      const before = pick(await detail(id));
      const r = await call("POST", `/api/admin/contents/${id}/${action}`, { version: before.version, ...body });
      assert.equal(r.status, 403, `${action} as OPERATOR answered ${r.status}`);
      assert.deepEqual(pick(await detail(id)), before, `a refused ${action} changed the content`);
    }

    // 1. OPERATOR: list, create, edit (autosave), submit; approve disabled and refused directly
    await login(operator);
    step("OPERATOR login: sidebar and /api/admin/me show OPERATOR");
    await page.goto(base + "/contents");
    await page.getByRole("heading", { name: "콘텐츠 관리" }).waitFor();
    const issues = await call("GET", "/api/admin/issues?size=100");
    const issue = issues.data.items.find((i) => i.status === "ACTIVE");
    assert(issue, "the database needs an ACTIVE issue");
    await page.goto(`${base}/issues/${issue.id}`);
    await page.getByRole("button", { name: "NEWS(뉴스) 만들기", exact: true }).click();
    await page.waitForURL(/\/contents\/\d+$/);
    const a = Number(new URL(page.url()).pathname.split("/").pop());
    await page.locator("#content-title").fill("역할 확인 콘텐츠 A " + Date.now());
    await page.getByRole("button", { name: "+ 섹션 추가" }).click();
    await page.getByPlaceholder("소제목").fill("확인 소제목");
    await page.getByRole("button", { name: /\+ 문단 추가/ }).click();
    await page.getByPlaceholder("문단 내용").fill("OPERATOR가 작성한 문단입니다.");
    const saved = await waitFor(a, (c) => c.body.sections.length === 1 && c.body.sections[0].paragraphs.length === 1
      && c.body.sections[0].paragraphs[0].text.length > 0, "autosave");
    assert.equal(saved.status, "DRAFT");
    assert.equal(saved.createdBy, operator.user);
    step(`OPERATOR create + edit autosave (content ${a}, version ${saved.version})`);
    await page.getByText(/^저장됨/).waitFor();
    await act("검수 요청");
    await waitFor(a, (c) => c.status === "REVIEW", "submit");
    step("OPERATOR submit -> REVIEW");
    await page.screenshot({ path: path.join(output, "content-roles-operator-review.png"), fullPage: true });
    await deniedForOperator(a, "approve", "승인", {});
    step("OPERATOR approve: button disabled, direct POST 403, status/version/approval unchanged");

    // 2. OPERATOR: exclude a second content; restore disabled and refused directly
    await page.goto(`${base}/issues/${issue.id}`);
    await page.getByRole("button", { name: "LIFE(생활 정보) 만들기", exact: true }).click();
    await page.waitForURL(/\/contents\/\d+$/);
    const b = Number(new URL(page.url()).pathname.split("/").pop());
    await act("제외", "역할 확인용 제외");
    await waitFor(b, (c) => c.status === "EXCLUDED", "exclude");
    step(`OPERATOR exclude (content ${b}) -> EXCLUDED`);
    await deniedForOperator(b, "restore", "복귀", { reason: "OPERATOR 복귀 시도" });
    step("OPERATOR restore: button disabled, direct POST 403, status/version unchanged");

    // 3. logout, then SYSTEM_ADMIN in the same tab: the allowed actions work
    await logout();
    step("logout: credentials gone, protected page -> /login, /api/admin/me 401");
    await login(admin);
    step("SYSTEM_ADMIN login in the same tab: sidebar and /api/admin/me show SYSTEM_ADMIN");
    await page.goto(`${base}/contents/${a}`);
    await page.getByRole("button", { name: "승인", exact: true }).waitFor();
    assert.equal(await page.getByRole("button", { name: "승인", exact: true }).isDisabled(), false);
    await act("승인");
    const approved = await waitFor(a, (c) => c.status === "APPROVED", "approve");
    assert.equal(approved.approvedBy, admin.user);
    step("SYSTEM_ADMIN approve -> APPROVED (approvedBy = admin)");
    await page.goto(`${base}/contents/${b}`);
    await page.getByRole("button", { name: "복귀", exact: true }).waitFor();
    await act("복귀", "역할 확인용 복귀");
    await waitFor(b, (c) => c.status === "DRAFT", "restore");
    step("SYSTEM_ADMIN restore -> DRAFT");

    // 4. back to OPERATOR: no SYSTEM_ADMIN rights or screen left over
    await logout();
    await login(operator);
    await page.goto(`${base}/contents/${a}`);
    await page.getByRole("button", { name: "보관", exact: true }).waitFor();
    await act("보관");
    await waitFor(a, (c) => c.status === "ARCHIVED", "archive");
    await deniedForOperator(a, "restore", "복귀", { reason: "OPERATOR 복귀 시도" });
    step("switch back to OPERATOR: archive allowed, restore disabled again and refused directly (no leftover admin rights)");
    await page.screenshot({ path: path.join(output, "content-roles-operator-archived.png"), fullPage: true });

    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ base, issueId: issue.id, contents: { a, b }, steps: steps.length, browserErrors: errors.length }, null, 2));
  } finally {
    await browser.close();
  }
}
main().catch((error) => { console.error(error.message); process.exitCode = 1; });
