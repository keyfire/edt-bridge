# Run 1C:EDT headless (no GUI window) so the edt-bridge MCP server serves the live model.
# Uses the EDT CLI's INTERACTIVE mode + a keepalive stdin; the bundle's DS component
# (OSGI-INF/edtbridge-mcp.xml) starts the server on activation, so no UI workbench is needed.
#
#   Start:  powershell -ExecutionPolicy Bypass -File run-headless.ps1 -Workspace <ws> [-EdtDir <dir>]
#   Stop :  Get-Process 1cedtcli,PING -ErrorAction SilentlyContinue | Stop-Process -Force
#
# Why interactive + keepalive: bare "1cedtcli -data ws" idles without starting the runtime;
# piping a command engages it, and the interactive shell keeps the OSGi framework (and the
# DS-started server) alive. `ping` keeps stdin open so the shell does not EOF-exit; `>nul`
# keeps ping output out of the pipe (only the single command reaches the CLI).
#
# Defaults assume a typical install; -EdtDir is auto-detected (newest installation) if omitted.
param(
  [Parameter(Mandatory = $true)][string]$Workspace,
  [string]$EdtDir,
  [int]$Port    = 8770,
  [int]$WaitSec = 360
)
$ErrorActionPreference = "Stop"

# Auto-detect the 1C:EDT installation if not given: newest under %LOCALAPPDATA%\1C\1cedtstart\installations\*\1cedt
if (-not $EdtDir) {
  $base = Join-Path $env:LOCALAPPDATA "1C\1cedtstart\installations"
  $EdtDir = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "1cedt" } |
    Where-Object { Test-Path (Join-Path $_ "1cedtcli.exe") } |
    Select-Object -First 1
  if (-not $EdtDir) { throw "Could not auto-detect 1C:EDT under $base; pass -EdtDir <...\1cedt>." }
}

# 1) SAFETY: never touch a GUI 1C:EDT (1cedt.exe). If one is running, abort - the user may be
#    working in it (and it holds the workspace lock). Only ever manage our own headless 1cedtcli.
if (Get-Process -Name '1cedt' -ErrorAction SilentlyContinue) {
  Write-Warning "A GUI 1C:EDT (1cedt.exe) is running - refusing to touch it. Close it first, or run headless on a separate -Workspace/-Port."
  exit 2
}
# Stop our headless CLI AND its keepalive wrappers (the bat's cmd + the long ping), so repeated
# launches don't leave orphans that keep the old jar locked.
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and ($_.CommandLine -match 'edtbridge-headless|edt_headless|999999 127\.0\.0\.1') } |
  ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }
Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
$lock = Join-Path $Workspace ".metadata\.lock"
if (Test-Path $lock) { try { Remove-Item $lock -Force -ErrorAction Stop } catch {} }
# Keep only the newest edt-bridge jar in dropins: two singletons of the same bundle make Equinox
# resolve an arbitrary (often older) one. Purge the rest so the freshly built jar is the one loaded.
$drop = Join-Path $EdtDir "dropins"
Get-ChildItem $drop -Filter "com.e1c.fresh.edtbridge_*.jar" -ErrorAction SilentlyContinue |
  Sort-Object Name -Descending | Select-Object -Skip 1 |
  ForEach-Object { try { Remove-Item $_.FullName -Force -ErrorAction Stop } catch {} }

# 2) launch headless via a hidden bat (cmd does the native pipe, clean ASCII stdin)
$cli = Join-Path $EdtDir "1cedtcli.exe"
$bat = Join-Path $env:TEMP "edtbridge-headless.bat"
$line = '(echo version& ping -n 999999 127.0.0.1 >nul) | "' + $cli + '" -data "' + $Workspace + '" -nl en_US'
# Propagate the write-tool auth token into the 1cedtcli JVM. The native EDT launcher does not
# inherit the parent shell's env reliably, so set it explicitly in the bat (env is read live by
# McpServer.token()). Only emitted when configured; secret lives in %TEMP%, never the repo.
$tokenLine = ""
if ($env:EDT_BRIDGE_TOKEN -and $env:EDT_BRIDGE_TOKEN.Trim()) {
  $tokenLine = 'set "EDT_BRIDGE_TOKEN=' + $env:EDT_BRIDGE_TOKEN.Trim() + '"' + "`r`n"
}
# Server-side switch that enables edt_evaluate (arbitrary BSL code execution). Off unless set.
$evalLine = ""
if ($env:EDT_BRIDGE_ALLOW_EVALUATE -and $env:EDT_BRIDGE_ALLOW_EVALUATE.Trim()) {
  $evalLine = 'set "EDT_BRIDGE_ALLOW_EVALUATE=' + $env:EDT_BRIDGE_ALLOW_EVALUATE.Trim() + '"' + "`r`n"
}
Set-Content -Path $bat -Value ("@echo off`r`n" + $tokenLine + $evalLine + $line) -Encoding ASCII
Start-Process -FilePath $bat -WorkingDirectory $EdtDir -WindowStyle Hidden
"Launched headless EDT (1cedtcli). Waiting up to $WaitSec s for the server + model..."

# 3) poll readiness: server up AND at least one project open (model loadable)
$deadline = (Get-Date).AddSeconds($WaitSec)
while ((Get-Date) -lt $deadline) {
  try {
    $s = (Invoke-WebRequest "http://127.0.0.1:$Port/status" -UseBasicParsing -TimeoutSec 3).Content
    if ($s -match '"openProjects":\["') { "READY: $s"; exit 0 }
  } catch {}
  Start-Sleep -Seconds 5
}
"NOT READY after $WaitSec s (1cedtcli alive: $([bool](Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue)))"
exit 1
