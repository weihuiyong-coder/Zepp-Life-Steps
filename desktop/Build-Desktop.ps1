$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $PSScriptRoot '.build'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$savedVariables = @{}
foreach ($name in @('ZEPP_DESKTOP_BUILD','NEXT_TELEMETRY_DISABLED','PYTHONIOENCODING')) {
    $savedVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
function Get-CheckedDownload([string]$Url, [string]$Destination, [string]$Hash) {
    if (-not (Test-Path -LiteralPath $Destination) -or (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -ne $Hash) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing -TimeoutSec 120
    }
    if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -ne $Hash) {
        throw ('Download checksum did not match: ' + $Destination)
    }
}
Push-Location $projectRoot
try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'The desktop build requires Windows x64.' }
    if (-not (Test-Path -LiteralPath $compiler)) { throw 'The Windows .NET Framework C# compiler was not found.' }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Install Node.js 24 first.' }
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) { throw 'Install Python 3.14 x64 first.' }
    New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath '.venv\Scripts\python.exe')) {
        & python -m venv .venv
        if ($LASTEXITCODE -ne 0) { throw 'Python environment setup failed.' }
    }
    $python = Join-Path $projectRoot '.venv\Scripts\python.exe'
    & $python -c "import sys,struct; sys.exit(0 if sys.version_info[:2] == (3,14) and struct.calcsize('P') == 8 else 1)"
    if ($LASTEXITCODE -ne 0) { throw 'Desktop builds require a Python 3.14 x64 virtual environment.' }
    & $python -m pip install -r desktop/requirements-build.txt
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    & npm.cmd ci --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'Node dependency installation failed.' }
    $env:ZEPP_DESKTOP_BUILD = '1'
    $env:NEXT_TELEMETRY_DISABLED = '1'
    $env:PYTHONIOENCODING = 'utf-8'
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Production build failed.' }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $installedNode = (Get-Command node).Source
    if ((Get-FileHash -LiteralPath $installedNode -Algorithm SHA256).Hash -eq '9A4EB5F1C29C6A2E93852EAD46B999E284A6A5CA8BAB4D4E241D587D025A52DE') {
        Copy-Item -LiteralPath $installedNode -Destination (Join-Path $buildRoot 'node.exe') -Force
    }
    Get-CheckedDownload 'https://nodejs.org/dist/v24.18.0/win-x64/node.exe' (Join-Path $buildRoot 'node.exe') '9A4EB5F1C29C6A2E93852EAD46B999E284A6A5CA8BAB4D4E241D587D025A52DE'
    Get-CheckedDownload 'https://www.python.org/ftp/python/3.14.7/python-3.14.7-embed-amd64.zip' (Join-Path $buildRoot 'python-3.14.7-embed-amd64.zip') 'D297E5FF019966817AD8502465176139F2D3D840FA4ED84B13BED399A6AB1F15'
    Invoke-WebRequest -Uri 'https://nodejs.org/dist/v24.18.0/SHASUMS256.txt' -OutFile (Join-Path $buildRoot 'node-SHASUMS256.txt') -UseBasicParsing -TimeoutSec 120
    Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/nodejs/node/v24.18.0/LICENSE' -OutFile (Join-Path $buildRoot 'NODE-LICENSE.txt') -UseBasicParsing -TimeoutSec 120
    & $python desktop/build_payload.py
    if ($LASTEXITCODE -ne 0) { throw 'Runtime payload build failed.' }
    $compilerArguments = @(
        '/nologo', '/target:winexe', '/platform:x64', '/optimize+',
        '/reference:System.Drawing.dll', '/reference:System.Windows.Forms.dll',
        '/reference:System.IO.Compression.dll', '/reference:System.Web.Extensions.dll',
        ('/win32manifest:' + (Join-Path $PSScriptRoot 'app.manifest')),
        ('/resource:' + (Join-Path $buildRoot 'payload.zip') + ',payload.zip'),
        ('/out:' + (Join-Path $buildRoot 'Zepp-Life-Steps.exe')),
        (Join-Path $PSScriptRoot 'Launcher.cs'), (Join-Path $buildRoot 'BuildInfo.cs'),
        (Join-Path $PSScriptRoot 'AssemblyInfo.cs')
    )
    & $compiler @compilerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed.' }
    & $python desktop/package_exe.py
    if ($LASTEXITCODE -ne 0) { throw 'Release packaging failed.' }
    Write-Host 'Desktop build complete. Output files are in dist/.'
} finally {
    foreach ($name in $savedVariables.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedVariables[$name], 'Process')
    }
    Pop-Location
}
