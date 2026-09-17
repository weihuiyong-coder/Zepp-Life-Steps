const { spawn } = require('child_process');
const { createHmac, randomBytes } = require('crypto');
const path = require('path');
const { DiagnosticError, retryDelay } = require('./diagnostics');

const labels = { access: '账号认证', login: '换取登录凭据', appToken: '获取应用令牌' };
function decodeResult(result) {
  if (result?.ok === true && ['loginToken', 'appToken', 'userId'].every(k => typeof result[k] === 'string' && result[k])) {
    return { loginToken: result.loginToken, appToken: result.appToken, userId: result.userId };
  }
  const stage = Object.hasOwn(labels, result?.stage) ? result.stage : 'access';
  const code = result?.code;
  const options = { stage, code, upstreamStatus: Number.isInteger(result?.upstreamStatus) ? result.upstreamStatus : undefined };
  if (code === 'UPSTREAM_RATE_LIMIT') {
    const delay = retryDelay(result.retryAfterHeader);
    throw new DiagnosticError(`${labels[stage]}受限：Zepp 返回 429，请暂停提交`, {
      ...options, status: 429, retryAfter: delay ?? 900, retryAfterSource: delay === undefined ? 'local' : 'upstream'
    });
  }
  const errors = {
    INVALID_INPUT: [400, '账号格式不正确，请输入手机号或邮箱及密码'],
    LOGIN_REJECTED: [401, `${labels[stage]}被 Zepp 拒绝，请检查账号、密码及账号地区`],
    UPSTREAM_TIMEOUT: [504, `${labels[stage]}超时，请稍后再试`],
    UPSTREAM_NETWORK_ERROR: [502, `${labels[stage]}连接失败，请检查网络或代理`],
    UPSTREAM_HTTP_ERROR: [502, `${labels[stage]}失败：上游 HTTP ${options.upstreamStatus || '错误'}`],
    UNEXPECTED_LOGIN_RESPONSE: [502, `${labels[stage]}未返回有效凭据，需检查账号状态或接口兼容性`]
  };
  const [status, message] = errors[code] || [500, '本机 Python 登录服务未正常完成，请检查运行环境'];
  throw new DiagnosticError(message, { ...options, status, code: errors[code] ? code : 'LOGIN_WORKER_ERROR' });
}

function runWorker(account, password, { spawnProcess = spawn, timeoutMs = 55000 } = {}) {
  return new Promise((resolve, reject) => {
    const python = process.env.ZEPP_PYTHON || path.join(process.cwd(), '.venv', process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
    const script = path.join(process.cwd(), 'server/python/login_worker.py');
    let child;
    let output = '';
    let settled = false;
    let timer;
    function finish(error, value) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      output = '';
      if (error) reject(error); else resolve(value);
    }
    const workerError = () => new DiagnosticError('本机 Python 登录服务无法启动或响应无效，请运行启动脚本安装依赖', { status: 500, stage: 'access', code: 'LOGIN_WORKER_ERROR' });
    try {
      child = spawnProcess(python, ['-I', '-u', script], { windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'] });
      timer = setTimeout(() => {
        child.kill();
        finish(new DiagnosticError('登录请求超时，请稍后再试', { status: 504, stage: 'access', code: 'UPSTREAM_TIMEOUT' }));
      }, timeoutMs);
      child.stdout.setEncoding('utf8');
      child.stdout.on('data', data => {
        output += data;
        if (Buffer.byteLength(output) > 65536) { child.kill(); finish(workerError()); }
      });
      // Never copy Python exception text to logs; it may contain upstream responses.
      child.stderr.on('data', () => {});
      child.stdin.on('error', () => finish(workerError()));
      child.on('error', () => finish(workerError()));
      child.on('close', code => {
        if (settled) return;
        if (code !== 0) return finish(workerError());
        let parsed;
        try { parsed = JSON.parse(output); } catch { return finish(workerError()); }
        finish(null, parsed);
      });
      child.stdin.end(JSON.stringify({ account, password }));
    } catch { if (child) child.kill(); finish(workerError()); }
  });
}

function createLoginClient({ worker = runWorker, clock = Date.now } = {}) {
  const cache = new Map();
  const salt = randomBytes(32);
  const keyFor = (account, password) => createHmac('sha256', salt).update(JSON.stringify([account.trim(), password])).digest('hex');
  return {
    async login(account, password) {
      const key = keyFor(account, password);
      for (const [storedKey, item] of cache) if (item.until <= clock()) cache.delete(storedKey);
      if (cache.has(key)) return { ...cache.get(key).tokens };
      const tokens = decodeResult(await worker(account, password));
      if (cache.size >= 8) cache.delete(cache.keys().next().value);
      cache.set(key, { until: clock() + 10 * 60 * 1000, tokens });
      return { ...tokens };
    },
    invalidate(account, password) { cache.delete(keyFor(account, password)); }
  };
}

module.exports = { decodeResult, runWorker, createLoginClient, loginClient: createLoginClient() };
