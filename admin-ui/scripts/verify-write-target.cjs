// Checks the target guard of the data-writing verify script (scripts/lib/write-target.cjs) without any app server:
// 1. the check function itself, case by case;
// 2. the real verify-content-roles.cjs started with wrong settings exits with an error, prints no password, and never
//    connects: a local listener on the target port counts connections (it is not the daily server; port 3000 is only
//    used when nothing listens there, otherwise that case is skipped and said so).
//   npm run verify:write-target
const assert = require("node:assert/strict");
const net = require("node:net");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const { checkWriteTarget, TargetError } = require("./lib/write-target.cjs");

const CREDS = { OPERATOR_USERNAME: "op-user", OPERATOR_PASSWORD: "op-pw-must-not-show", ADMIN_USERNAME: "admin-user", ADMIN_PASSWORD: "admin-pw-must-not-show" };
const CRED_NAMES = Object.keys(CREDS);
let failures = 0;
const check = (name, fn) => {
  try { fn(); console.log("PASS " + name); } catch (e) { failures++; console.log("FAIL " + name + "\n  " + e.message.split("\n")[0]); }
};
const refused = (env, pattern) => () => {
  assert.throws(() => checkWriteTarget(env, CRED_NAMES), (e) => e instanceof TargetError && pattern.test(e.message)
    && /VERIFY_CONFIRM_VERIFY_DB=13098/.test(e.message) && !e.message.includes("must-not-show"));
};

// 1. the check function
check("no VERIFY_UI_URL", refused({ ...CREDS }, /not set: this script writes data and has no default/));
check("daily UI http://localhost:3000", refused({ ...CREDS, VERIFY_UI_URL: "http://localhost:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, /port 3000 is the daily UI/));
check("daily UI http://127.0.0.1:3000/", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3000/", VERIFY_CONFIRM_VERIFY_DB: "3000" }, /port 3000 is the daily UI/));
check("dev UI http://127.0.0.1:3001", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3001", VERIFY_CONFIRM_VERIFY_DB: "3001" }, /port 3001 is the dev UI/));
check("external host", refused({ ...CREDS, VERIFY_UI_URL: "http://example.com:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /host must be localhost or 127\.0\.0\.1/));
check("look-alike host localhost.example.com", refused({ ...CREDS, VERIFY_UI_URL: "http://localhost.example.com:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /host must be/));
check("invalid URL", refused({ ...CREDS, VERIFY_UI_URL: "127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /must use http:\/\/|not a valid URL/));
check("not a URL at all", refused({ ...CREDS, VERIFY_UI_URL: "::not a url::", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /not a valid URL/));
check("https / other protocol", refused({ ...CREDS, VERIFY_UI_URL: "https://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /must use http:\/\//));
check("user info in URL", refused({ ...CREDS, VERIFY_UI_URL: "http://u:p@127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /must not contain a user name or password/));
check("no explicit port", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1", VERIFY_CONFIRM_VERIFY_DB: "80" }, /explicit port/));
check("path in URL", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098/contents", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /origin only/));
check("query in URL", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098/?x=1", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /origin only/));
check("opt-in missing", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098" }, /VERIFY_CONFIRM_VERIFY_DB is not set/));
check("opt-in with another port", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13093" }, /must repeat the UI port 13098/));
check("opt-in as yes/true", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "yes" }, /must repeat the UI port/));
check("missing password", refused({ ...CREDS, ADMIN_PASSWORD: "", VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, /Set ADMIN_PASSWORD/));
check("allowed: http://127.0.0.1:13098 + confirm 13098", () => {
  assert.deepEqual(checkWriteTarget({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13098" }, CRED_NAMES),
    { base: "http://127.0.0.1:13098", port: "13098" });
});
check("allowed: http://localhost:13093/ + confirm 13093", () => {
  assert.deepEqual(checkWriteTarget({ ...CREDS, VERIFY_UI_URL: "http://localhost:13093/", VERIFY_CONFIRM_VERIFY_DB: " 13093 " }, CRED_NAMES),
    { base: "http://localhost:13093", port: "13093" });
});

// 2. the real script refuses before connecting
function listen(port) {
  return new Promise((resolve) => {
    const server = net.createServer((s) => { server.hits++; s.destroy(); });
    server.hits = 0;
    server.once("error", () => resolve(null));
    server.listen(port, "127.0.0.1", () => resolve(server));
  });
}
async function scriptCases() {
  const free = await listen(0);
  const port = String(free.address().port);
  const daily = await listen(3000);
  const base = { ...process.env };
  for (const k of ["VERIFY_UI_URL", "VERIFY_CONFIRM_VERIFY_DB", ...CRED_NAMES]) delete base[k];
  const cases = [
    ["script: no VERIFY_UI_URL", { ...CREDS }, free],
    ["script: daily http://127.0.0.1:3000 with confirm", { ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, daily],
    ["script: daily http://localhost:3000 with confirm", { ...CREDS, VERIFY_UI_URL: "http://localhost:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, daily],
    ["script: local port without confirm", { ...CREDS, VERIFY_UI_URL: `http://127.0.0.1:${port}` }, free],
    ["script: local port with wrong confirm", { ...CREDS, VERIFY_UI_URL: `http://127.0.0.1:${port}`, VERIFY_CONFIRM_VERIFY_DB: "1" }, free],
    ["script: external host", { ...CREDS, VERIFY_UI_URL: `http://example.com:${port}`, VERIFY_CONFIRM_VERIFY_DB: port }, free],
    ["script: invalid URL", { ...CREDS, VERIFY_UI_URL: "not a url", VERIFY_CONFIRM_VERIFY_DB: port }, free],
  ];
  for (const [name, env, listener] of cases) {
    if (!listener) { console.log(`SKIP ${name} (port 3000 is in use here; the function cases above cover it)`); continue; }
    const before = listener.hits;
    const r = spawnSync(process.execPath, [path.join(__dirname, "verify-content-roles.cjs")], { env: { ...base, ...env }, encoding: "utf8", timeout: 30000 });
    const out = (r.stdout || "") + (r.stderr || "");
    check(name, () => {
      assert.equal(r.status, 1, `exit ${r.status}`);
      assert.match(out, /VERIFY_UI_URL=http:\/\/127\.0\.0\.1:13098 VERIFY_CONFIRM_VERIFY_DB=13098/);
      assert(!out.includes("must-not-show"), "a password was printed");
      assert.doesNotMatch(out, /PASS /);
      assert.equal(listener.hits - before, 0, "the script connected to the target");
    });
  }
  free.close(); if (daily) daily.close();
}

scriptCases().then(() => {
  console.log(failures ? `${failures} check(s) failed` : "all checks passed");
  process.exitCode = failures ? 1 : 0;
});
