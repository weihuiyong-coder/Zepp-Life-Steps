# Windows EXE 构建

`Launcher.cs`、`AssemblyInfo.cs` 和 `app.manifest` 是免安装 EXE 的启动器源码。它将 Node.js、Python 和 Next.js 生产版装入一个 EXE，首次运行时解压到 `%LOCALAPPDATA%\ZeppLifeSteps`。网页就绪后打开浏览器；退出时通过 Windows Job Object 停止后台服务及其子进程。

## 从源码构建

使用 Windows x64，安装 Node.js 24 和 Python 3.14 x64。构建脚本使用 Windows 自带的 .NET Framework C# 编译器，不需要安装 Visual Studio。

在仓库根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\desktop\Build-Desktop.ps1
```

脚本会安装锁定的依赖、构建网页，下载并校验 Node.js 24.18.0 与 Python 3.14.7 嵌入式运行环境，然后输出：

- `dist/Zepp步数助手.exe`
- `dist/Zepp步数助手-Windows免安装版.zip`
- `dist/EXE版使用说明.txt`

中间文件位于 `desktop/.build` 和 `.next-desktop`，这些目录不提交到 Git。Python 运行依赖固定在 `requirements-build.txt`。第三方许可证保留在打包后的运行目录内。

Release 附件是已验证的构建产物。重新构建可能因构建时间、路径和压缩元数据而产生不同哈希；发布文件的 SHA256 以对应 Release 的 `SHA256SUMS.txt` 为准。

## 无账号自检

下面的自检会启动临时服务，检查网页、静态资源和内置 Python 桥接，最后停止服务。输入使用无效账号格式，不向 Zepp 发送登录请求。

```powershell
$cache = Join-Path $env:TEMP ('zepp-selftest-' + [guid]::NewGuid().ToString('N'))
$report = Join-Path (Get-Location) 'dist\selftest.json'
$arguments = '--self-test --cache-root "' + $cache + '" --report "' + $report + '"'
Start-Process -FilePath '.\dist\Zepp步数助手.exe' -ArgumentList $arguments -WindowStyle Hidden -Wait
Get-Content -LiteralPath $report
```

`--no-browser` 可用于窗口生命周期检查，启动后不会自动打开浏览器。正式使用直接双击 EXE 即可。
