"""Publish the exact delivered, signed APK. No signing private key is used."""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib

REPO = "weihuiyong-coder/Zepp-Life-Steps"
TAG = "android-v1.0.0"
SOURCE = "cde992dbadef4e4067cfabf1c244caf6811f9703"
API = "https://api.github.com/repos/" + REPO
APK_NAME = "ZeppSteps-1.0.0.apk"
EXPECTED_APK_SHA256 = "f2da1d92acb857a18623e7e2269fb9934187ab2414646d3adf54c816e35bc856"
NOTES = """## Zepp步数助手 Android v1.0.0（预览版）

下载附件 `ZeppSteps-1.0.0.apk` 安装；最低支持 Android 8.0。
这是手机独立运行的原生应用，不需要电脑、Termux、Root 或自建服务器，需要联网。

### 使用方法
1. 先在官方 Zepp Life 完成微信运动绑定。
2. 输入 Zepp Life 手机号／邮箱和密码，不是微信密码。
3. 点击“验证登录”检查登录；这个按钮不会修改步数。
4. 填写今天的目标总步数，点击“提交步数”并确认。
5. “Zepp 已接受”只表示提交被接口接受，请到微信运动确认最终同步结果。

日期按北京时间当天处理；输入的是目标总步数，不是追加步数。
账号密码默认不保存；选择保存后在手机本机使用 Android Keystore + AES-256-GCM 加密。
仅申请互联网权限，没有广告或分析组件。此应用不是 Zepp 或微信官方产品。

### 验证范围与限制
交付记录：编译、35 项模拟接口／数据边界检查、APK v2/v3 签名验证和 ZIP 完整性检查通过。
本次发布再次校验完整 APK SHA-256，确保与已交付安装包逐字节一致。
**未经过安卓模拟器或真机安装运行测试，未使用真实账号验证登录、提交及微信端同步。**
第三方接口可能变化或触发验证、限流；预览版不保证所有账号或设备可用。
仅用于自己的账号，不用于竞赛、保险、奖励等依赖真实运动数据的活动。

### 版本与维护
包名：com.weihuiyong.zeppsteps；versionName 1.0.0；versionCode 10000。
安卓应用源码来自 929e5cb8d10ba7da30755f22b1779257b61c27e3。
本标签对应 release/android-v1.0.0 的应用源码快照；CI 配置保留在 feature/android-apk-20260918。
Windows v1.0.0 发布及 main 分支未修改。
附件不包含账号、密码、令牌、私有签名密钥或 PRIVATE 维护备份。
校验值见 SHA256SUMS.txt。
"""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def prepare(base_path: Path) -> dict[str, bytes]:
    manifest = json.loads(Path(__file__).with_name("manifest.json").read_text())
    base = base_path.read_bytes()
    if sha256(base) != manifest["base_sha256"]:
        raise RuntimeError("Unsigned build hash mismatch; publication stopped")
    ops = json.loads(zlib.decompress(base64.b64decode(manifest["patch_zlib_base64"], validate=True)))
    chunks = []
    for op in ops:
        if isinstance(op, list):
            start, length = op
            if not isinstance(start, int) or not isinstance(length, int) or start < 0 or length < 0 or start + length > len(base):
                raise ValueError("Invalid APK byte-copy range")
            chunks.append(base[start:start + length])
        elif isinstance(op, str):
            chunks.append(base64.b64decode(op, validate=True))
        else:
            raise ValueError("Invalid APK patch operation")
    apk = b"".join(chunks)
    if len(apk) != 41819 or sha256(apk) != EXPECTED_APK_SHA256:
        raise RuntimeError("Signed APK differs from delivered file; publication stopped")
    files = {APK_NAME: apk, "README-Android.txt": NOTES.encode("utf-8")}
    files["SHA256SUMS.txt"] = "".join(f"{sha256(data)}  {name}\n" for name, data in files.items()).encode("ascii")
    return files


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward the publication token to another host.


def request(method: str, url: str, *, payload=None, data=None, content_type="application/json"):
    if urllib.parse.urlsplit(url).hostname not in {"api.github.com", "uploads.github.com"}:
        raise ValueError("Unexpected GitHub API host")
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    headers = {"Authorization": "Bearer " + os.environ["GH_TOKEN"],
               "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2026-03-10",
               "Content-Type": content_type, "User-Agent": "ZeppSteps-Android-Publisher"}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.build_opener(NoRedirect).open(req, timeout=60) as response:
        body = response.read()
        return json.loads(body) if body else None


def publish(files: dict[str, bytes]) -> None:
    try:
        release = request("GET", API + "/releases/tags/" + TAG)
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        release = request("POST", API + "/releases", payload={
            "tag_name": TAG, "target_commitish": SOURCE,
            "name": "Android v1.0.0 · Zepp步数助手（预览版）", "body": NOTES,
            "draft": True, "prerelease": True, "make_latest": "false"})
    if release["tag_name"] != TAG or release["name"] != "Android v1.0.0 · Zepp步数助手（预览版）":
        raise RuntimeError("Existing release identity differs; will not overwrite it")
    assets = request("GET", API + f"/releases/{release['id']}/assets")
    by_name = {asset["name"]: asset for asset in assets}
    if set(by_name) - set(files):
        raise RuntimeError("Unexpected assets in target release; will not change it")
    upload_base = release["upload_url"].split("{", 1)[0]
    for name, data in files.items():
        digest = "sha256:" + sha256(data)
        asset = by_name.get(name)
        if asset is None:
            mime = "application/vnd.android.package-archive" if name.endswith(".apk") else "text/plain; charset=utf-8"
            asset = request("POST", upload_base + "?" + urllib.parse.urlencode({"name": name}),
                            data=data, content_type=mime)
        if asset["state"] != "uploaded" or asset["size"] != len(data) or asset.get("digest") != digest:
            raise RuntimeError("Uploaded asset verification failed: " + name)
        print("Verified asset:", name, digest)
    if release["draft"]:
        release = request("PATCH", API + f"/releases/{release['id']}", payload={
            "draft": False, "prerelease": True, "make_latest": "false"})
    print("Published:", release["html_url"])


if __name__ == "__main__":
    verify_only = len(sys.argv) == 3 and sys.argv[1] == "--verify-only"
    base_path = Path(sys.argv[2]) if verify_only else Path("/tmp/zepp-unsigned-build/ZeppSteps-1.0.0-unsigned.apk")
    output = prepare(base_path)
    if verify_only:
        for filename, data in output.items():
            print(filename, len(data), sha256(data))
    else:
        publish(output)
