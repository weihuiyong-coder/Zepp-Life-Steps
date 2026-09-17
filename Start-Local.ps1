param(
    [ValidateRange(1024, 65535)][int]$Port = 3107,
    [switch]$UseProxy
)

$ErrorActionPreference = 'Stop'
$originalNoProxy = $env:NO_PROXY
$originalTelemetry = $env:NEXT_TELEMETRY_DISABLED
$originalPython = $env:ZEPP_PYTHON
$originalLocalPort = $env:ZEPP_LOCAL_PORT
Push-Location $PSScriptRoot
try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Please install Node.js first.' }
    if (-not (Test-Path -LiteralPath '.venv/Scripts/python.exe')) {
        & python -m venv .venv
        if ($LASTEXITCODE -ne 0) { throw 'Python environment setup failed.' }
    }
    & ./.venv/Scripts/python.exe -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('requests') else 1)"
    if ($LASTEXITCODE -ne 0) {
        & ./.venv/Scripts/python.exe -m pip install -r server/python/requirements.txt
        if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
    }
    $env:ZEPP_PYTHON = Join-Path $PSScriptRoot '.venv/Scripts/python.exe'
    $env:ZEPP_LOCAL_PORT = [string]$Port
    if (-not (Test-Path -LiteralPath 'node_modules/next/dist/bin/next')) {
        & npm.cmd ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
    }
    $env:NEXT_TELEMETRY_DISABLED = '1'
    if (-not $UseProxy) {
        # Process-local only: leave the Windows/user proxy settings unchanged.
        $env:NO_PROXY = (@($originalNoProxy, 'localhost', '127.0.0.1', '::1', '.huami.com', '.zepp.com') | Where-Object { $_ }) -join ','
    }
    if (-not (Test-Path -LiteralPath '.next/BUILD_ID')) {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Production build failed.' }
    }
    Write-Host "Local app: http://127.0.0.1:$Port (Ctrl+C to stop)"
    Write-Host 'Diagnostics omit account names, passwords and tokens.'
    & node node_modules/next/dist/bin/next start -H 127.0.0.1 -p $Port
    if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error.' }
} finally {
    $env:NO_PROXY = $originalNoProxy
    $env:NEXT_TELEMETRY_DISABLED = $originalTelemetry
    $env:ZEPP_PYTHON = $originalPython
    $env:ZEPP_LOCAL_PORT = $originalLocalPort
    Pop-Location
}
