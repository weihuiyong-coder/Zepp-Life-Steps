"""Private stdin/stdout bridge. Credentials never appear in process arguments or logs."""
from __future__ import annotations
import json
import sys
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
import requests
from vendor import zepp_login as zepp


class Session(requests.Session):
    def __init__(self):
        super().__init__()
        self.last_response = None

    def request(self, method, url, **kwargs):
        # The upstream library advertises optional decoders even when not installed.
        headers = {k: v for k, v in (kwargs.get('headers') or {}).items() if k.lower() != 'accept-encoding'}
        headers['Accept-Encoding'] = requests.utils.default_headers()['Accept-Encoding']
        kwargs['headers'] = headers
        self.last_response = None
        self.last_response = super().request(method, url, **kwargs)
        return self.last_response


def failure(stage, response):
    result = {'ok': False, 'stage': stage}
    if response is None:
        return {**result, 'code': 'UPSTREAM_NETWORK_ERROR'}
    status = response.status_code
    result['upstreamStatus'] = status
    if status == 429:
        value = response.headers.get('retry-after')
        return {**result, 'code': 'UPSTREAM_RATE_LIMIT', 'retryAfterHeader': value if isinstance(value, str) and len(value) < 128 else None}
    redirect_error = parse_qs(urlparse(response.headers.get('location', '')).query).get('error', [])
    if status in (401, 403) or '401' in redirect_error:
        return {**result, 'code': 'LOGIN_REJECTED'}
    return {**result, 'code': 'UPSTREAM_HTTP_ERROR' if status >= 400 else 'UNEXPECTED_LOGIN_RESPONSE'}


def login(account, password, session_factory=Session):
    if not isinstance(account, str) or not isinstance(password, str) or not password or len(account) > 254 or len(password) > 256:
        return {'ok': False, 'code': 'INVALID_INPUT', 'stage': 'access'}
    try:
        account = zepp.normalize_account(account)
    except zepp.ZeppLifeLoginError:
        return {'ok': False, 'code': 'INVALID_INPUT', 'stage': 'access'}
    stage = 'access'
    try:
        with session_factory() as session:
            try:
                access = zepp.get_registration_access_code(session, account, password, timeout=15)
                stage = 'login'
                login_token, user_id = zepp.exchange_access_code(session, access, timeout=15)
                stage = 'appToken'
                app_token = zepp.get_app_token(session, login_token, timeout=15)
                return {'ok': True, 'loginToken': login_token, 'appToken': app_token, 'userId': user_id}
            except requests.Timeout:
                return {'ok': False, 'code': 'UPSTREAM_TIMEOUT', 'stage': stage}
            except (requests.RequestException, zepp.ZeppLifeLoginError):
                # Vendor errors may contain raw credentials or tokens; never serialize them.
                return failure(stage, session.last_response)
    except Exception:
        return {'ok': False, 'code': 'LOGIN_WORKER_ERROR', 'stage': stage}


def main():
    try:
        raw = sys.stdin.buffer.read(8193)
        if len(raw) > 8192:
            raise ValueError()
        payload = json.loads(raw)
        result = login(payload.get('account'), payload.get('password'))
    except Exception:
        result = {'ok': False, 'code': 'INVALID_INPUT', 'stage': 'access'}
    # stdout is a private pipe to Node, never the server's log stream.
    sys.stdout.write(json.dumps(result, ensure_ascii=True))
    sys.stdout.flush()


if __name__ == '__main__':
    main()
