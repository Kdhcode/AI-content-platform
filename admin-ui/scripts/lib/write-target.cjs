// Pre-checks for verify scripts that WRITE data (create / edit / approve / archive content). Pure: reads only the
// given environment values, opens no browser and sends no request, so a wrong target is refused before anything
// reaches a server. Never echoes credentials.
//
// Required:
//   VERIFY_UI_URL                 the UI of a verify environment: http://localhost:<port> or http://127.0.0.1:<port>,
//                                 explicit port, no path / query / fragment / user info
//   VERIFY_CONFIRM_VERIFY_DB      the same port number, typed by the person running it to confirm that this UI's backend
//                                 is connected to a verify DB copy (e.g. aicontent_verify_operator_20261019)
// Refused ports: 3000 (daily UI -> aicontent_main) and 3001 (dev UI -> aicontent), on both hosts.
//
// Limit: the port rule and the confirmation are a guard against running it by mistake, not proof. Nothing here can see
// which database the backend behind that UI uses; the person confirming must know it (RUNBOOK / TASK.md record the
// verify environments and their ports). The app deliberately exposes no API that reveals its database name.

const ALLOWED_HOSTS = new Set(["localhost", "127.0.0.1"]);
const REFUSED_PORTS = new Map([["3000", "daily UI (aicontent_main)"], ["3001", "dev UI (aicontent)"]]);

const USAGE = [
  "Run it only against a verify environment whose backend uses a verify DB copy, for example:",
  "  VERIFY_UI_URL=http://127.0.0.1:13098 VERIFY_CONFIRM_VERIFY_DB=13098 \\",
  "  OPERATOR_USERNAME=... OPERATOR_PASSWORD=... ADMIN_USERNAME=... ADMIN_PASSWORD=... npm run verify:content-roles",
  "(PowerShell: set each as $env:NAME = '...' first.) VERIFY_CONFIRM_VERIFY_DB must repeat the UI port.",
].join("\n");

class TargetError extends Error {}
// Messages never repeat what was typed: a mistyped URL can still hold a user name, password or token (in the user
// info, host, scheme, path or query). Only the port is shown, which the URL parser accepts as digits only.
const refuse = (why) => { throw new TargetError(`${why}\n${USAGE}`); };

/**
 * @param {Record<string, string | undefined>} env usually process.env
 * @param {string[]} credentialNames env names that must be set (values are never printed)
 * @returns {{ base: string, port: string }}
 */
function checkWriteTarget(env, credentialNames = []) {
  const raw = (env.VERIFY_UI_URL || "").trim();
  if (!raw) refuse("VERIFY_UI_URL is not set: this script writes data and has no default address.");
  let url;
  try { url = new URL(raw); } catch { refuse("VERIFY_UI_URL is not a valid URL (value not shown)."); }
  if (url.protocol !== "http:") refuse("VERIFY_UI_URL must use http:// (local UI).");
  if (url.username || url.password) refuse("VERIFY_UI_URL must not contain a user name or password.");
  if (!ALLOWED_HOSTS.has(url.hostname)) refuse("VERIFY_UI_URL host must be localhost or 127.0.0.1.");
  if (!url.port) refuse("VERIFY_UI_URL needs an explicit port of a verify environment (e.g. :13098).");
  if (REFUSED_PORTS.has(url.port)) refuse(`VERIFY_UI_URL port ${url.port} is the ${REFUSED_PORTS.get(url.port)}, not a verify environment.`);
  if (url.pathname !== "/" || url.search || url.hash) refuse("VERIFY_UI_URL must be the UI origin only (no path, query or fragment).");
  const confirm = (env.VERIFY_CONFIRM_VERIFY_DB || "").trim();
  if (!confirm) refuse("VERIFY_CONFIRM_VERIFY_DB is not set: confirm that this UI's backend uses a verify DB copy.");
  if (confirm !== url.port) refuse(`VERIFY_CONFIRM_VERIFY_DB must repeat the UI port ${url.port}.`);
  const missing = credentialNames.filter((n) => !env[n]);
  if (missing.length) refuse(`Set ${missing.join(", ")} (test accounts of that verify environment).`);
  return { base: url.origin, port: url.port };
}

module.exports = { checkWriteTarget, TargetError, REFUSED_PORTS, USAGE };
