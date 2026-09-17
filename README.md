# Zepp-Life-Steps 整合版

保留之前仓库的完整网页：账号、密码、步数、随机步数、历史记录、统计和设置。后端改用 miloce 的 Python 登录接口，再将得到的用户 ID 和应用令牌用于步数提交。

## Windows 免安装版（推荐）

从 [Releases 下载最新版 EXE 或转发 ZIP](https://github.com/weihuiyong-coder/Zepp-Life-Steps/releases/latest)。支持 Windows 10/11 x64，无需另外安装 Node.js、Python。

双击 EXE，首次启动等待内置环境解压，随后自动打开网页。输入账号、密码和目标步数后点击“更新步数”。使用时保留启动窗口，关闭窗口即停止本机服务。端口占用时会自动选择空闲端口。

[EXE 使用说明](docs/windows-exe.txt) · [启动器源码与构建方法](desktop/README.md)

## 从源码在 Windows 本机运行

1. 安装 [Node.js 24 LTS](https://nodejs.org/) 和 [Python 3.14](https://www.python.org/downloads/windows/)。本次测试版本为 Node.js 24.18.0、Python 3.14.7；Python 安装时勾选“Add python.exe to PATH”。
2. 将压缩包完整解压到自己的文件夹，不要直接在压缩包内启动。
3. 双击 **Start-Local.cmd**。首次启动需要联网，会自动安装依赖、创建 Python 环境并构建网页，请等待启动完成。
4. 在浏览器打开 **http://127.0.0.1:3107**，输入自己的 Zepp Life 账号、密码和目标步数，点击“更新步数”。

使用时保持启动窗口打开，关闭窗口或按 Ctrl+C 即停止服务。再次使用时双击同一个启动文件即可，通常不需要重新安装依赖。每位同事都在自己的电脑上启动，127.0.0.1 始终指向当前电脑。

也可以在本目录打开 PowerShell，运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-Local.ps1
```

启动入口中的 ExecutionPolicy Bypass 只对本次启动的 PowerShell 进程生效，不修改系统执行策略。

只有步数接口返回成功码 `1` 才会显示“Zepp 已接受…步”。微信和支付宝的数据同步由平台处理，需要在对应应用确认，不能仅凭网页成功提示判定同步完成。

修改源码后先执行 `npm run build`，再重新启动。端口被占用时可用 `-Port 3108`。默认仅在当前进程对 `.huami.com`、`.zepp.com` 直连，不更改 Windows 代理设置；对照原有代理可使用 `-UseProxy`。

## 两个仓库各自负责什么

| 部分 | 来源 |
| --- | --- |
| 网页与运动数据提交格式 | LiuJun-tao/Zepp-Life-Steps，基础提交 `f1e1910734614b316268ae31e8da5d22345ab72e` |
| 登录、获取 login_token / app_token / user_id | miloce/Zepp-Life-Steps，提交 `1a6c24044a2aced028ac811c3eda8f00f465746c` |
| Node 与 Python 连接、错误分类、日志脱敏、限流暂停 | 本次新增的本机整合层 |

`server/python/vendor/zepp_login.py` 与 miloce 仓库的 `zepp登录接口.py` 字节一致，没有改写其登录参数。

登录请求顺序为：

1. `api-user.huami.com/registrations/{account}/tokens`
2. `api-mifit.zepp.com/v2/client/login`
3. `api-mifit.zepp.com/v1/client/app_tokens`
4. 将返回的 `user_id` 和 `app_token` 交给之前的步数提交接口 `api-mifit-cn2.huami.com/v1/data/band_data.json`。

新版库申请的是 `com.huami.midong` 授权。2026-09-17 已在 Windows 本机完成真实账号测试，服务端确认步数接口接受提交，测试者也确认成功。微信/支付宝的同步结果未独立核验；其他账号和网络环境仍以实际返回结果为准。接口拒绝时会显示具体阶段，不会用登录成功冒充步数提交成功，也不会自动更换接口反复请求。

## 常见问题

- 提示找不到 Node.js 或 Python：完成上方安装后重新双击启动文件。Python 安装器需要启用 PATH 选项。
- 网页打不开：等待启动窗口出现 `ready started server`。首次安装需要下载依赖，启动窗口中显示失败时先按对应信息处理。
- 3107 端口占用：在解压目录打开 PowerShell，执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Start-Local.ps1 -Port 3108`，随后访问 http://127.0.0.1:3108 。
- 页面提示请求过于频繁：按页面倒计时等待，再手动提交。
- 页面提示成功但微信/支付宝暂未变化：检查 Zepp Life 的第三方账号绑定，并在相应应用确认同步结果。

交付包只包含源码、依赖清单、启动入口和说明；不包含测试账号、密码、令牌、运行日志、浏览器历史或本机安装环境。首次启动会在同事自己的电脑上生成运行环境。

## 凭据与限流

- 账号密码通过服务器内部管道交给 Python，不放入命令行参数；不写入文件或日志。
- 登录令牌不返回浏览器。仅在服务端进程内缓存 10 分钟，最多 8 个条目；缓存键是使用随机密钥计算的账号/密码摘要，不保存明文密码。停止服务后缓存消失。
- 步数接口拒绝令牌或业务提交时清除此账号缓存；不自动重新登录或重发步数。
- 429 优先遵守 Retry-After；没有有效时间时，本地保护性暂停 15 分钟。自动更新关闭，刷新页面保留暂停状态；结束后不自动提交。
- 服务端暂停记录仅在当前进程有效，重启后重置。浏览器本地历史记录仍按原网页保存账号、步数和时间。
- 服务仅监听本机，提交接口校验网页来源。此版本需要本机 Node + Python，不能直接按旧 README 部署到 Vercel。

## 验证

```powershell
npm test
.\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v
```

12 项 Node 测试和 6 项 Python 测试使用模拟上游，覆盖新库调用顺序、手机号区号、完整提交链、上游业务失败、限流、缓存隔离与过期、凭据脱敏、Python 管道和超时，并验证 Next.js 内部转发的来源校验。
本次另已完成真实账号提交：服务端记录 `stage: update, success: true`，且测试者确认成功。只有上游步数接口返回成功码 `1` 才会产生该记录；微信/支付宝的同步结果未独立核验。
