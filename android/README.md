# Zepp步数助手 Android 1.0.0

原生 Android 客户端，将本仓库的电脑版登录和步数上传链路迁移至手机本机。不是仅打开电脑网页的 WebView 外壳，不依赖 Node、Python、Termux、Root 或自建服务器。需要手机能够访问 Zepp/华米的 HTTPS 接口。

## 使用

安装已签名 APK。输入与电脑版相同的 Zepp Life 手机号/邮箱及密码，可先点击“验证登录”；填写 0–100000 的今日目标总步数，点击“提交步数”并确认。数据按北京时间当天提交，不是追加步数。需要事先在官方 Zepp Life 中完成微信运动绑定。应用仅能确认 Zepp 接口接受，不能确认微信已经完成同步。

默认不持久保存账号密码。勾选“加密保存在本机”后使用 Android Keystore AES-256-GCM；不启用云备份和设备迁移，不记录密码/令牌，不加载广告或分析服务。清除账号可删除保存的凭据。没有定时刷步数或后台任务，不自动重试提交；429 按 Retry-After 冷却。

## 来源与兼容性

原登录逻辑：`server/python/vendor/zepp_login.py`，原提交逻辑和完整数据模板：`lib/step-client.js`。原生客户端保留账号规范化、三阶段登录参数、客户端请求头、上传端点、设备标识和完整 band_data 模板，仅把日期与步数替换为本次输入。构建时从原文件提取模板并校验字段，不手工复制长字符串。

最低 Android 8.0 / API 26；target API 35；纯 Java/Android 框架，不包含 ABI 专用原生库。不使用 Google Play 服务。

第三方接口可能变化、触发验证或限流。仅供本人账号使用，不用于竞赛、保险、奖励等依赖真实运动数据的活动。非 Zepp/微信官方产品。

## 构建

需要 JDK 17+、Node.js、curl、zip，以及 Android SDK 的 platforms;android-35 和 build-tools;35.0.0。在仓库根目录运行：

```bash
export ANDROID_HOME=/path/to/android-sdk
bash android/build.sh
```

构建先进行纯 JVM 协议/数据/错误处理测试，之后进行资源打包、Java 编译、D8 转换和 zipalign。输出 `android/out/ZeppSteps-1.0.0-unsigned.apk`。必须使用自己的私有签名密钥签名后才能安装：

```bash
java -jar android/out/apksigner.jar sign --ks /private/path/release.p12 --out ZeppSteps-1.0.0.apk android/out/ZeppSteps-1.0.0-unsigned.apk
java -jar android/out/apksigner.jar verify --verbose --print-certs ZeppSteps-1.0.0.apk
```

不要把签名私钥、账号密码或令牌提交到 GitHub。后续覆盖安装需要保持包名和签名证书一致。CI 不生成或上传私钥。

## 测试边界

自动化测试使用合成账号与模拟响应，验证正常登录/提交、输入边界、特殊字符、北京时间日期、原模板保留、重定向 access 解析、401/429、无效响应和提交拒绝。编译或模拟测试通过不代表真实账号已完成登录，也不代表微信已同步；真实账号仅应由用户在自己手机上输入验证。
