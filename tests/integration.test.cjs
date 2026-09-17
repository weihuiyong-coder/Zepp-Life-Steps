const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');
const { EventEmitter } = require('node:events');
const { PassThrough } = require('node:stream');
const { createLoginClient, decodeResult, runWorker } = require('../lib/miloce-login');
const { DiagnosticError } = require('../lib/diagnostics');
const { createRequestGuard } = require('../lib/request-guard');
const root = path.resolve(__dirname, '..');

function load(file, overrides, logs = []) {
  const filename = path.join(root, file);
  const nativeRequire = createRequire(filename);
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    module, exports: module.exports, process: { env: { ZEPP_LOCAL_PORT: '3107' } },
    require: name => Object.hasOwn(overrides, name) ? overrides[name] : nativeRequire(name),
    console: Object.fromEntries(['info', 'error', 'log'].map(k => [k, (...args) => logs.push(args)]))
  }, { filename });
  return module.exports;
}
function setup(login = async () => ({ appToken: 'SECRET_APP_TOKEN', userId: '12345' }), update = async () => ({ code: 1 })) {
  const logs = [], calls = [];
  const handler = load('pages/api/update-steps.js', {
    '../../lib/miloce-login': { loginClient: { login: async (...args) => { calls.push('login'); return login(...args); }, invalidate: () => calls.push('invalidate') } },
    '../../lib/step-client': { updateSteps: async (...args) => { calls.push(['update', ...args]); return update(...args); } },
    '../../lib/request-guard': { requestGuard: createRequestGuard() }
  }, logs);
  return { logs, calls, async submit(body = { account: 'test@example.invalid', password: 'SECRET_PASSWORD', steps: 1234 }, origin = 'http://127.0.0.1:3107', host = '127.0.0.1:3107') {
    const res = { headers: {}, setHeader(k,v) { this.headers[k] = v; }, status(n) { this.statusCode = n; return this; }, json(body) { this.body = body; return this; } };
    await handler({ method: 'POST', headers: { host, origin }, body }, res);
    return res;
  } };
}

test('successful update uses new login user ID and app token, never exposes credentials', async () => {
  const h = setup();
  const res = await h.submit();
  assert.equal(res.statusCode, 200);
  assert.deepEqual(h.calls, ['login', ['update', '12345', 'SECRET_APP_TOKEN', 1234]]);
  assert.match(res.body.message, /同步请在对应应用确认/);
  assert.doesNotMatch(JSON.stringify([res.body, h.logs]), /SECRET_/);
});

test('login failure stops before step submission', async () => {
  const h = setup(async () => decodeResult({ ok: false, stage: 'login', code: 'LOGIN_REJECTED', upstreamStatus: 401 }));
  const res = await h.submit();
  assert.equal(res.statusCode, 401);
  assert.equal(res.body.stage, 'login');
  assert.deepEqual(h.calls, ['login']);
});

test('step failure stays failure and invalidates the cached token without retry', async () => {
  const h = setup(undefined, async () => { throw new DiagnosticError('步数提交未被接受', { stage: 'update', code: 'UPDATE_REJECTED' }); });
  const res = await h.submit();
  assert.equal(res.statusCode, 502);
  assert.equal(res.body.success, false);
  assert.equal(res.body.stage, 'update');
  assert.equal(h.calls.length, 3);
  assert.equal(h.calls.at(-1), 'invalidate');
});

test('429 blocks the next request locally and does not retry upstream', async () => {
  const h = setup(async () => decodeResult({ ok: false, stage: 'access', code: 'UPSTREAM_RATE_LIMIT', upstreamStatus: 429, retryAfterHeader: '120' }));
  const first = await h.submit();
  const second = await h.submit();
  assert.equal(first.statusCode, 429);
  assert.equal(first.headers['Retry-After'], '120');
  assert.equal(second.body.code, 'LOCAL_COOLDOWN');
  assert.equal(h.calls.length, 1);
});

test('bad steps and cross-origin requests do not call upstream', async () => {
  const h = setup();
  for (const steps of [-1, 1.5, 100001, null, true, {}]) {
    assert.equal((await h.submit({ account: 'a', password: 'p', steps })).statusCode, 400);
  }
  assert.equal((await h.submit(undefined, 'https://untrusted.example')).statusCode, 403);
  assert.equal(h.calls.length, 0);
});

test('Next internal worker host is accepted only for the configured public origin', async () => {
  const h = setup();
  assert.equal((await h.submit(undefined, 'http://127.0.0.1:3107', '127.0.0.1:54606')).statusCode, 200);
  const count = h.calls.length;
  assert.equal((await h.submit(undefined, 'http://127.0.0.1:3108', '127.0.0.1:54606')).statusCode, 403);
  assert.equal(h.calls.length, count);
});

test('login cache expires and a changed password cannot reuse the token', async () => {
  let now = 0, count = 0;
  const client = createLoginClient({ clock: () => now, worker: async () => { count++; return { ok: true, loginToken: 'SECRET_LOGIN', appToken: 'SECRET_APP', userId: '12345' }; } });
  await client.login('test@example.invalid', 'one');
  await client.login('test@example.invalid', 'one');
  assert.equal(count, 1);
  await client.login('test@example.invalid', 'two');
  assert.equal(count, 2);
  now = 600001;
  await client.login('test@example.invalid', 'one');
  assert.equal(count, 3);
  client.invalidate('test@example.invalid', 'one');
  await client.login('test@example.invalid', 'one');
  assert.equal(count, 4);
});

test('step wire payload encodes base64 plus signs and uses the actual user ID', async () => {
  let sent;
  const client = load('lib/step-client.js', { axios: { create: () => ({ post: async (url, data, config) => { sent = { url, data, config }; return { data: { code: 1 } }; } }) } });
  await client.updateSteps('12345', 'SECRET_APP', 0);
  const form = new URLSearchParams(sent.data);
  assert.equal(form.get('userid'), '12345');
  assert.equal(sent.config.headers.apptoken, 'SECRET_APP');
  const records = JSON.parse(form.get('data_json'));
  assert.ok(records[0].data_hr.includes('+'));
  assert.equal(JSON.parse(records[0].summary).stp.ttl, 0);
});

test('step business rejection never returns success or copies upstream secrets', async () => {
  const client = load('lib/step-client.js', { axios: { create: () => ({ post: async () => ({ data: { code: 0, secret: 'SECRET_TOKEN' } }) }) } });
  await assert.rejects(client.updateSteps('12345', 'SECRET_APP', 1234), e => e.code === 'UPDATE_REJECTED' && !e.message.includes('SECRET_'));
});

test('Python bridge passes secrets through stdin only and discards stderr', async () => {
  let captured;
  const result = await runWorker('SECRET_ACCOUNT', 'SECRET_PASSWORD', { spawnProcess(exe, args, options) {
    captured = { exe, args, options, input: '' };
    const child = new EventEmitter();
    Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill() {} });
    child.stdin.on('data', chunk => captured.input += chunk);
    child.stdin.on('finish', () => queueMicrotask(() => {
      child.stderr.write('SECRET_TRACE');
      child.stdout.write(JSON.stringify({ ok: true, loginToken: 'SECRET_LOGIN', appToken: 'SECRET_APP', userId: '12345' }));
      child.emit('close', 0);
    }));
    return child;
  } });
  assert.doesNotMatch(JSON.stringify([captured.exe, captured.args, captured.options]), /SECRET_/);
  assert.equal(JSON.parse(captured.input).password, 'SECRET_PASSWORD');
  assert.equal(result.appToken, 'SECRET_APP');
});

test('malformed Python output is a safe error, never echoed', async () => {
  await assert.rejects(runWorker('a', 'p', { spawnProcess() {
    const child = new EventEmitter();
    Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill() {} });
    child.stdin.on('finish', () => queueMicrotask(() => { child.stdout.write('SECRET_BAD_OUTPUT'); child.emit('close', 0); }));
    return child;
  } }), e => e.code === 'LOGIN_WORKER_ERROR' && !e.message.includes('SECRET_'));
});

test('worker timeout terminates the child and is not retried', async () => {
  let killed = 0;
  await assert.rejects(runWorker('a', 'p', { timeoutMs: 5, spawnProcess() {
    const child = new EventEmitter();
    Object.assign(child, { stdin: new PassThrough(), stdout: new PassThrough(), stderr: new PassThrough(), kill() { killed++; } });
    return child;
  } }), e => e.status === 504);
  assert.equal(killed, 1);
});
