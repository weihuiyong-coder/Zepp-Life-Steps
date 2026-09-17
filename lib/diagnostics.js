const stageLabels = {
  access: '获取登录凭据', login: '换取登录令牌',
  appToken: '获取应用令牌', update: '提交步数'
};

class DiagnosticError extends Error {
  constructor(message, { stage, code, status = 502, upstreamStatus, upstreamCode, retryAfter, retryAfterSource } = {}) {
    super(message);
    Object.assign(this, { stage, code, status, upstreamStatus, upstreamCode, retryAfter, retryAfterSource });
  }
}

function retryDelay(value, now = Date.now()) {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined;
  const text = String(value).trim();
  const seconds = /^\d+$/.test(text) ? Number(text) : Math.ceil((Date.parse(text) - now) / 1000);
  return Number.isSafeInteger(seconds) && seconds >= 0 && now + seconds * 1000 <= 8640000000000000
    ? Math.max(1, seconds) : undefined;
}

// Never return/log Axios errors, request bodies, redirect URLs or upstream bodies.
function normalizeError(error, stage) {
  if (error instanceof DiagnosticError) return error;
  const label = stageLabels[stage] || '处理请求';
  const upstreamStatus = Number.isInteger(error.response?.status) ? error.response.status : undefined;
  if (upstreamStatus === 429) {
    const suppliedDelay = retryDelay(error.response.headers?.['retry-after']);
    return new DiagnosticError(`${label}受限：华米返回 HTTP 429，请稍后再试，暂勿连续提交`, {
      stage, code: 'UPSTREAM_RATE_LIMIT', status: 429, upstreamStatus,
      retryAfter: suppliedDelay ?? 900, retryAfterSource: suppliedDelay === undefined ? 'local' : 'upstream'
    });
  }
  if (['ECONNABORTED', 'ETIMEDOUT'].includes(error.code)) {
    return new DiagnosticError(`${label}超时，请检查网络或代理设置`, { stage, code: 'UPSTREAM_TIMEOUT', status: 504 });
  }
  if (upstreamStatus) {
    return new DiagnosticError(`${label}失败：上游 HTTP ${upstreamStatus}，请检查接口及代理设置`, {
      stage, code: 'UPSTREAM_HTTP_ERROR', upstreamStatus
    });
  }
  if (error.isAxiosError) {
    return new DiagnosticError(`${label}失败：无法连接上游服务，请检查网络、DNS 或代理设置`, {
      stage, code: 'UPSTREAM_NETWORK_ERROR'
    });
  }
  return new DiagnosticError(`${label}失败：服务器内部错误`, { stage, code: 'INTERNAL_ERROR', status: 500 });
}

function errorDetails(error) {
  return { stage: error.stage, code: error.code, upstreamStatus: error.upstreamStatus, upstreamCode: error.upstreamCode,
    retryAfter: error.retryAfter, retryAfterSource: error.retryAfterSource };
}

module.exports = { DiagnosticError, normalizeError, errorDetails, retryDelay };
