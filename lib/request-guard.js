const { DiagnosticError } = require('./diagnostics');

// Shared by this local server process: changing accounts must not bypass an IP cooldown.
function createRequestGuard(now = Date.now) {
  let busy = false;
  let blockedUntil = 0;
  let retryAfterSource;
  return {
    enter() {
      const remaining = Math.ceil((blockedUntil - now()) / 1000);
      if (remaining > 0) {
        throw new DiagnosticError('仍在限流暂停期间，本次没有向华米发送请求', {
          stage: 'access', code: 'LOCAL_COOLDOWN', status: 429,
          retryAfter: remaining, retryAfterSource
        });
      }
      if (busy) throw new DiagnosticError('已有请求正在处理，请勿重复提交', { code: 'REQUEST_IN_PROGRESS', status: 409 });
      busy = true;
    },
    pause(seconds, source) { blockedUntil = Math.max(blockedUntil, now() + seconds * 1000); retryAfterSource = source; },
    leave() { busy = false; }
  };
}

module.exports = { createRequestGuard, requestGuard: createRequestGuard() };
