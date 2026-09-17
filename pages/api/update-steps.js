const { randomUUID } = require('crypto');
const { loginClient } = require('../../lib/miloce-login');
const { updateSteps } = require('../../lib/step-client');
const { normalizeError, errorDetails } = require('../../lib/diagnostics');
const { requestGuard } = require('../../lib/request-guard');

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ success: false, message: '方法不允许' });
  }
  const host = req.headers?.host;
  // Next 13 forwards API traffic to a random loopback worker port, replacing Host.
  // Compare Origin to the configured public port, not that internal worker port.
  const publicPort = process.env.ZEPP_LOCAL_PORT || '3107';
  const allowedOrigins = [`http://127.0.0.1:${publicPort}`, `http://localhost:${publicPort}`];
  if (!/^(127\.0\.0\.1|localhost):\d+$/.test(host || '') || !allowedOrigins.includes(req.headers.origin)) {
    return res.status(403).json({ success: false, message: '请从本机网页提交请求' });
  }
  const requestId = randomUUID();
  res.setHeader('X-Request-Id', requestId);
  let stage = 'access';
  let acquired = false;
  try {
    const { account, password, steps } = req.body || {};
    if (typeof account !== 'string' || !account.trim() || account.length > 254 || typeof password !== 'string' || !password || password.length > 256) {
      return res.status(400).json({ success: false, message: '账号和密码不能为空', requestId });
    }
    const targetSteps = steps === undefined || steps === ''
      ? Math.floor(Math.random() * 10000) + 20000
      : (typeof steps === 'number' || typeof steps === 'string') ? Number(steps) : NaN;
    if (!Number.isInteger(targetSteps) || targetSteps < 0 || targetSteps > 100000) {
      return res.status(400).json({ success: false, message: '步数必须是 0 到 100000 的整数', requestId });
    }
    requestGuard.enter();
    acquired = true;
    const { appToken, userId } = await loginClient.login(account.trim(), password);
    stage = 'update';
    await updateSteps(userId, appToken, targetSteps);
    console.info('zepp-request', { requestId, stage, success: true });
    return res.status(200).json({ success: true, message: `Zepp 已接受 ${targetSteps} 步；微信、支付宝同步请在对应应用确认`, requestId });
  } catch (error) {
    const failure = normalizeError(error, stage);
    if (stage === 'update' && (failure.upstreamStatus === 401 || failure.upstreamStatus === 403 || failure.code === 'UPDATE_REJECTED')) {
      loginClient.invalidate(req.body.account, req.body.password);
    }
    if (failure.code === 'UPSTREAM_RATE_LIMIT') requestGuard.pause(failure.retryAfter, failure.retryAfterSource);
    if (failure.retryAfter) res.setHeader('Retry-After', String(failure.retryAfter));
    const details = errorDetails(failure);
    console.error('zepp-request', { requestId, success: false, ...details });
    return res.status(failure.status).json({ success: false, message: failure.message, requestId, ...details });
  } finally {
    if (acquired) requestGuard.leave();
  }
};
