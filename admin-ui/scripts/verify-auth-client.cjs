const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

async function main() {
  const values = new Map();
  const redirects = [];
  const requests = [];
  const responses = [];
  const sandbox = {
    exports: {}, btoa: value => Buffer.from(value, 'binary').toString('base64'),
    unescape, encodeURIComponent,
    sessionStorage: {
      getItem: key => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
      removeItem: key => values.delete(key),
    },
    window: { location: { pathname: '/issues', replace: url => redirects.push(url) } },
    fetch: async (url, options) => {
      requests.push({ url, options });
      return responses.shift()();
    },
  };
  const source = fs.readFileSync(path.join(__dirname, '../src/lib/api.ts'), 'utf8');
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText, sandbox);
  const { api, setCredentials, hasCredentials } = sandbox.exports;
  const response = status => ({ status, ok: status === 200, statusText: '',
    json: async () => status === 200 ? { success: true, data: { connected: true } }
      : { success: false, error: { code: 'UNAUTHORIZED', message: 'Authentication required' } } });

  await assert.rejects(api('/api/admin/issues'), error => error.status === 401);
  assert.equal(requests.length, 0);
  assert.equal(redirects.pop(), '/login');

  setCredentials(' operator ', 'test-only');
  responses.push(() => response(200));
  assert.equal((await api('/api/admin/dashboard')).connected, true);
  assert.equal(Boolean(requests.at(-1).options.headers.Authorization), true);
  assert.equal(Buffer.from(requests.at(-1).options.headers.Authorization.slice(6), 'base64')
    .toString().split(':')[0], 'operator');

  let finishOldRequest;
  responses.push(() => new Promise(resolve => { finishOldRequest = resolve; }));
  const oldRequest = api('/api/admin/issues');
  setCredentials('operator', 'new-test-only');
  finishOldRequest(response(401));
  await assert.rejects(oldRequest, error => error.status === 401);
  assert.equal(hasCredentials(), true);
  assert.equal(redirects.length, 0);

  responses.push(() => response(401));
  await assert.rejects(api('/api/admin/issues'), error => error.status === 401);
  assert.equal(hasCredentials(), false);
  assert.equal(redirects.pop(), '/login');
  console.log('PASS: missing credentials, authenticated requests, stale 401, current 401');
}
main().catch(() => { console.error('FAIL: auth client verification'); process.exitCode = 1; });
