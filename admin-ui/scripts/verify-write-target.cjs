// Checks the target guard of the data-writing verify script (scripts/lib/write-target.cjs) without any app server:
// 1. the check function itself, case by case, including inputs that carry fake secrets (they must never be echoed);
// 2. the real verify-content-roles.cjs started with wrong settings exits with an error, prints no secret, and never
//    connects to the target port.
// Connections are counted by listeners this test opens itself on BOTH 127.0.0.1 and ::1 for the same port, so a
// "localhost" connection is caught whichever address family it uses. Children run with async spawn: the parent's event
// loop keeps accepting and counting connections while it waits (spawnSync would block it and could count 0 falsely).
// A positive control (a test-only child that does connect) must be counted, or the run fails. Port 3000 is used only
// when this test can open it itself (nothing listens there); it never talks to a daily server.
//   npm run verify:write-target
const assert = require("node:assert/strict");
const net = require("node:net");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { checkWriteTarget, TargetError } = require("./lib/write-target.cjs");

const FAKE = "fake-secret-7f3a91";   // made-up marker; no real credential is used anywhere in this test
const CREDS = { OPERATOR_USERNAME: "op-user", OPERATOR_PASSWORD: `op-${FAKE}`, ADMIN_USERNAME: "admin-user", ADMIN_PASSWORD: `admin-${FAKE}` };
const CRED_NAMES = Object.keys(CREDS);
const CHILD_TIMEOUT_MS = 30000;
let failures = 0;
const check = async (name, fn) => {
  try { await fn(); console.log("PASS " + name); } catch (e) { failures++; console.log("FAIL " + name + "\n  " + String(e.message).split("\n")[0]); }
};
const refused = (env, pattern) => () => {
  assert.throws(() => checkWriteTarget(env, CRED_NAMES), (e) => {
    assert(e instanceof TargetError, "not a TargetError");
    assert.match(e.message, pattern);
    assert.match(e.message, /VERIFY_CONFIRM_VERIFY_DB=13098/, "no usage example");
    assert(!e.message.includes(FAKE), "a secret from the input was echoed");
    return true;
  });
};

// ---------------------------------------------------------------- 1. the check function
async function functionCases() {
  const ok = { ...CREDS, VERIFY_CONFIRM_VERIFY_DB: "13098" };
  await check("no VERIFY_UI_URL", refused({ ...CREDS }, /not set: this script writes data and has no default/));
  await check("daily UI http://localhost:3000", refused({ ...CREDS, VERIFY_UI_URL: "http://localhost:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, /port 3000 is the daily UI/));
  await check("daily UI http://127.0.0.1:3000/", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3000/", VERIFY_CONFIRM_VERIFY_DB: "3000" }, /port 3000 is the daily UI/));
  await check("dev UI http://127.0.0.1:3001", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3001", VERIFY_CONFIRM_VERIFY_DB: "3001" }, /port 3001 is the dev UI/));
  await check("external host", refused({ ...ok, VERIFY_UI_URL: "http://example.com:13098" }, /host must be localhost or 127\.0\.0\.1/));
  await check("look-alike host localhost.example.com", refused({ ...ok, VERIFY_UI_URL: "http://localhost.example.com:13098" }, /host must be/));
  await check("invalid URL", refused({ ...ok, VERIFY_UI_URL: "127.0.0.1:13098" }, /must use http:\/\/|not a valid URL/));
  await check("not a URL at all", refused({ ...ok, VERIFY_UI_URL: "::not a url::" }, /not a valid URL \(value not shown\)/));
  await check("https / other protocol", refused({ ...ok, VERIFY_UI_URL: "https://127.0.0.1:13098" }, /must use http:\/\//));
  await check("user info in URL", refused({ ...ok, VERIFY_UI_URL: "http://u:p@127.0.0.1:13098" }, /must not contain a user name or password/));
  await check("no explicit port", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1", VERIFY_CONFIRM_VERIFY_DB: "80" }, /explicit port/));
  await check("path in URL", refused({ ...ok, VERIFY_UI_URL: "http://127.0.0.1:13098/contents" }, /origin only/));
  await check("query in URL", refused({ ...ok, VERIFY_UI_URL: "http://127.0.0.1:13098/?x=1" }, /origin only/));
  // fake secrets inside the address: refused, and the secret is not in the message
  await check("secret: unparsable URL with password", refused({ ...ok, VERIFY_UI_URL: `http://admin:${FAKE}@[bad-host:13098` }, /not a valid URL \(value not shown\)/));
  await check("secret: token as the whole value", refused({ ...ok, VERIFY_UI_URL: `Bearer ${FAKE}` }, /not a valid URL/));
  await check("secret: user info password", refused({ ...ok, VERIFY_UI_URL: `http://admin:${FAKE}@127.0.0.1:13098` }, /must not contain a user name or password/));
  await check("secret: user info on an external host", refused({ ...ok, VERIFY_UI_URL: `http://admin:${FAKE}@example.com:13098` }, /must not contain a user name or password/));
  await check("secret: token in the query", refused({ ...ok, VERIFY_UI_URL: `http://127.0.0.1:13098/?token=${FAKE}` }, /origin only/));
  await check("secret: token in the path", refused({ ...ok, VERIFY_UI_URL: `http://127.0.0.1:13098/${FAKE}` }, /origin only/));
  await check("secret: token in the host", refused({ ...ok, VERIFY_UI_URL: `http://${FAKE}.example.com:13098` }, /host must be/));
  await check("secret: token as the scheme", refused({ ...ok, VERIFY_UI_URL: `${FAKE}://127.0.0.1:13098` }, /must use http:\/\//));
  await check("opt-in missing", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098" }, /VERIFY_CONFIRM_VERIFY_DB is not set/));
  await check("opt-in with another port", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "13093" }, /must repeat the UI port 13098/));
  await check("opt-in as yes/true", refused({ ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:13098", VERIFY_CONFIRM_VERIFY_DB: "yes" }, /must repeat the UI port/));
  await check("missing password", refused({ ...ok, ADMIN_PASSWORD: "", VERIFY_UI_URL: "http://127.0.0.1:13098" }, /Set ADMIN_PASSWORD/));
  await check("allowed: http://127.0.0.1:13098 + confirm 13098", () => {
    assert.deepEqual(checkWriteTarget({ ...ok, VERIFY_UI_URL: "http://127.0.0.1:13098" }, CRED_NAMES), { base: "http://127.0.0.1:13098", port: "13098" });
  });
  await check("allowed: http://localhost:13093/ + confirm 13093", () => {
    assert.deepEqual(checkWriteTarget({ ...CREDS, VERIFY_UI_URL: "http://localhost:13093/", VERIFY_CONFIRM_VERIFY_DB: " 13093 " }, CRED_NAMES),
      { base: "http://localhost:13093", port: "13093" });
  });
}

// ---------------------------------------------------------------- 2. connection counting with real child processes
/** Opens one listener on host:port; resolves null when it cannot (port taken, or no IPv6 here). */
function listenOn(host, port) {
  return new Promise((resolve) => {
    const sockets = new Set();
    const server = net.createServer((s) => { server.hits++; sockets.add(s); s.on("close", () => sockets.delete(s)); s.destroy(); });
    server.hits = 0;
    server.closeAll = () => new Promise((done) => { for (const s of sockets) s.destroy(); server.close(() => done()); });
    server.once("error", () => resolve(null));
    server.listen({ host, port, exclusive: true }, () => resolve(server));
  });
}
/** A counter for one port on 127.0.0.1 and, when available, ::1. Null when the IPv4 side cannot be opened. */
async function counter(port) {
  const v4 = await listenOn("127.0.0.1", port);
  if (!v4) return null;
  const v6 = await listenOn("::1", v4.address().port);
  return {
    port: String(v4.address().port), v4, v6,
    hits: () => v4.hits + (v6 ? v6.hits : 0),
    close: async () => { await v4.closeAll(); if (v6) await v6.closeAll(); },
  };
}
/** Runs a child without blocking this process; settles on exit, spawn error or timeout (child killed). */
function run(args, env) {
  return new Promise((resolve) => {
    let out = "";
    let settled = false;
    const child = spawn(process.execPath, args, { env, stdio: ["ignore", "pipe", "pipe"] });
    const finish = (r) => { if (!settled) { settled = true; clearTimeout(timer); resolve({ ...r, out }); } };
    const timer = setTimeout(() => { child.kill(); finish({ code: null, error: `timed out after ${CHILD_TIMEOUT_MS} ms` }); }, CHILD_TIMEOUT_MS);
    child.stdout.on("data", (d) => { out += d; });
    child.stderr.on("data", (d) => { out += d; });
    child.on("error", (e) => finish({ code: null, error: "spawn failed: " + e.message }));
    child.on("close", (code) => finish({ code, error: null }));
  });
}
/** Let pending accept / connection callbacks run before reading the counters. */
const settle = () => new Promise((r) => setTimeout(r, 300));

async function scriptCases() {
  const target = await counter(0);
  assert(target, "could not open a local test listener");
  const daily = await counter(3000);          // only when this test can open 3000 itself
  const counters = [target, daily].filter(Boolean);
  const base = { ...process.env };
  for (const k of ["VERIFY_UI_URL", "VERIFY_CONFIRM_VERIFY_DB", ...CRED_NAMES]) delete base[k];
  const families = (c) => `127.0.0.1${c.v6 ? " + ::1" : " only (no IPv6 here)"}`;
  console.log(`listeners: test port ${target.port} on ${families(target)}; port 3000 ${daily ? "on " + families(daily) : "not opened (in use here)"}`);
  try {
    // positive controls: a test-only child that does connect MUST be counted, over 127.0.0.1 and over "localhost"
    const connector = `const net=require("net");const [h,p]=process.argv.slice(1);const s=net.connect({host:h,port:Number(p)},()=>{s.end();process.exit(0)});s.on("error",e=>{console.error(e.code);process.exit(2)});`;
    for (const host of ["127.0.0.1", "localhost", ...(target.v6 ? ["::1"] : [])]) {
      await check(`positive control: child connects to ${host}:${target.port} -> counted`, async () => {
        const before = target.hits();
        const r = await run(["-e", connector, host, target.port], base);
        await settle();
        assert.equal(r.error, null, r.error);
        assert.equal(r.code, 0, `connector exit ${r.code}: ${r.out.trim()}`);
        assert(target.hits() - before >= 1, "the counter missed a real connection");
      });
    }

    // the real script with wrong settings: exits 1, shows the usage, prints no secret, never connects
    const cases = [
      ["script: no VERIFY_UI_URL", { ...CREDS }, target],
      ["script: daily http://127.0.0.1:3000 with confirm", { ...CREDS, VERIFY_UI_URL: "http://127.0.0.1:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, daily],
      ["script: daily http://localhost:3000 with confirm", { ...CREDS, VERIFY_UI_URL: "http://localhost:3000", VERIFY_CONFIRM_VERIFY_DB: "3000" }, daily],
      ["script: local test port without confirm", { ...CREDS, VERIFY_UI_URL: `http://127.0.0.1:${target.port}` }, target],
      ["script: localhost test port without confirm", { ...CREDS, VERIFY_UI_URL: `http://localhost:${target.port}` }, target],
      ["script: local test port with wrong confirm", { ...CREDS, VERIFY_UI_URL: `http://127.0.0.1:${target.port}`, VERIFY_CONFIRM_VERIFY_DB: "1" }, target],
      ["script: external host", { ...CREDS, VERIFY_UI_URL: `http://example.com:${target.port}`, VERIFY_CONFIRM_VERIFY_DB: target.port }, target],
      ["script: invalid URL with a fake password", { ...CREDS, VERIFY_UI_URL: `http://admin:${FAKE}@[bad:${target.port}`, VERIFY_CONFIRM_VERIFY_DB: target.port }, target],
      ["script: user info with a fake password", { ...CREDS, VERIFY_UI_URL: `http://admin:${FAKE}@127.0.0.1:${target.port}`, VERIFY_CONFIRM_VERIFY_DB: target.port }, target],
      ["script: fake token in the query", { ...CREDS, VERIFY_UI_URL: `http://127.0.0.1:${target.port}/?token=${FAKE}`, VERIFY_CONFIRM_VERIFY_DB: target.port }, target],
    ];
    for (const [name, env, listener] of cases) {
      if (!listener) { console.log(`SKIP ${name} (port 3000 is in use here; the function cases above cover it)`); continue; }
      await check(name, async () => {
        const before = counters.map((c) => c.hits());
        const r = await run([path.join(__dirname, "verify-content-roles.cjs")], { ...base, ...env });
        await settle();
        assert.equal(r.error, null, r.error);
        // the safety property first, so a failure says whether anything reached a listener
        const after = counters.map((c) => c.hits());
        assert.deepEqual(after, before, `the script connected to a test listener (${after.map((n, i) => n - before[i]).join(" / ")} new connection(s))`);
        assert(!r.out.includes(FAKE), "a secret was printed");
        assert.equal(r.code, 1, `exit ${r.code}`);
        assert.match(r.out, /VERIFY_UI_URL=http:\/\/127\.0\.0\.1:13098 VERIFY_CONFIRM_VERIFY_DB=13098/);
        assert.doesNotMatch(r.out, /PASS /);
      });
    }
  } finally {
    for (const c of counters) await c.close();
  }
}

(async () => {
  await functionCases();
  await scriptCases();
  console.log(failures ? `${failures} check(s) failed` : "all checks passed");
  process.exitCode = failures ? 1 : 0;
})().catch((e) => { console.error(e.message); process.exitCode = 1; });
