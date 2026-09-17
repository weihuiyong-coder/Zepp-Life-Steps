import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server/python'))
import login_worker
import requests


class Response:
    def __init__(self, status=200, data=None, headers=None):
        self.status_code = status
        self.data = data
        self.headers = headers or {}
        self.text = json.dumps(data)

    def json(self):
        return self.data


class FakeSession:
    def __init__(self, replies):
        self.replies = replies
        self.calls = []
        self.last_response = None

    def __enter__(self): return self
    def __exit__(self, *args): pass

    def send(self, method, url, **kwargs):
        self.calls.append((method, url, kwargs))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            self.last_response = None
            raise reply
        self.last_response = reply
        return reply

    def post(self, url, **kwargs): return self.send('POST', url, **kwargs)
    def get(self, url, **kwargs): return self.send('GET', url, **kwargs)


class LoginTests(unittest.TestCase):
    def test_real_library_sequence_and_phone_normalization(self):
        session = FakeSession([
            Response(data={'access': 'SECRET_ACCESS'}),
            Response(data={'token_info': {'login_token': 'SECRET_LOGIN', 'user_id': '12345'}}),
            Response(data={'token_info': {'app_token': 'SECRET_APP'}})
        ])
        result = login_worker.login('13800000000', 'SECRET_PASSWORD', lambda: session)
        self.assertTrue(result['ok'])
        self.assertEqual(result['userId'], '12345')
        self.assertIn('/registrations/%2B8613800000000/tokens', session.calls[0][1])
        self.assertEqual(session.calls[0][2]['data']['json_response'], 'true')
        self.assertEqual(session.calls[1][1], 'https://api-mifit.zepp.com/v2/client/login')
        self.assertEqual(session.calls[1][2]['data']['app_name'], 'com.huami.midong')
        self.assertEqual(session.calls[2][1], 'https://api-mifit.zepp.com/v1/client/app_tokens')

    def test_429_stops_and_preserves_retry_after(self):
        session = FakeSession([Response(429, {'secret': 'SECRET'}, {'retry-after': '120'})])
        result = login_worker.login('test@example.invalid', 'SECRET', lambda: session)
        self.assertEqual(result['code'], 'UPSTREAM_RATE_LIMIT')
        self.assertEqual(result['retryAfterHeader'], '120')
        self.assertEqual(len(session.calls), 1)
        self.assertNotIn('SECRET', json.dumps(result))

    def test_redirect_rejection_is_not_an_internal_error(self):
        session = FakeSession([Response(303, {}, {'location': 'https://example.invalid/?error=401'})])
        result = login_worker.login('test@example.invalid', 'SECRET', lambda: session)
        self.assertEqual(result['code'], 'LOGIN_REJECTED')

    def test_missing_token_does_not_leak_vendor_exception(self):
        session = FakeSession([Response(data={'access': 'SECRET_ACCESS'}), Response(data={'token_info': {'secret': 'SECRET_TOKEN'}})])
        result = login_worker.login('test@example.invalid', 'SECRET_PASSWORD', lambda: session)
        self.assertEqual(result['code'], 'UNEXPECTED_LOGIN_RESPONSE')
        self.assertEqual(result['stage'], 'login')
        self.assertNotIn('SECRET', json.dumps(result))

    def test_timeout_keeps_correct_stage(self):
        session = FakeSession([Response(data={'access': 'SECRET_ACCESS'}), requests.Timeout('SECRET_URL')])
        result = login_worker.login('test@example.invalid', 'SECRET_PASSWORD', lambda: session)
        self.assertEqual(result['code'], 'UPSTREAM_TIMEOUT')
        self.assertEqual(result['stage'], 'login')
        self.assertNotIn('SECRET', json.dumps(result))

    def test_invalid_account_never_creates_session(self):
        def forbidden(): raise AssertionError('Network must not be attempted')
        self.assertEqual(login_worker.login('not-an-account', 'p', forbidden)['code'], 'INVALID_INPUT')


if __name__ == '__main__': unittest.main()
