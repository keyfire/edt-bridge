# Toggle the edt-bridge headless EDT server: one action = start if stopped, stop if running.
# Made for a desktop shortcut (see make-shortcut.ps1) so the bridge can be used without
# launching the EDT GUI. Safe by design: never touches a running GUI 1C:EDT (1cedt.exe).
#
#   Double-click the shortcut (or run):
#     powershell -ExecutionPolicy Bypass -File toggle-headless.ps1 -Workspace <ws> [-EdtDir <dir>]
#
# Detection: the server is "running" when 1cedtcli.exe is alive OR the port answers /status.
# Start delegates to run-headless.ps1 (same folder) - it purges stale jars, launches 1cedtcli
# with the keepalive stdin and polls readiness. Stop kills our own headless processes only
# (1cedtcli + the keepalive bat), exactly like run-headless.ps1's own cleanup.
# Write tools need EDT_BRIDGE_TOKEN in the environment - run-headless.ps1 propagates it.
param(
  [Parameter(Mandatory = $true)][string]$Workspace,
  [string]$EdtDir,
  [int]$Port    = 8770,
  [int]$WaitSec = 360,
  [switch]$NoPause             # for scripted use: do not wait for Enter at the end
)
$ErrorActionPreference = "Continue"

function Done([int]$code) {
  if (-not $NoPause) { Read-Host "`nPress Enter to close" | Out-Null }
  exit $code
}

# Never touch a GUI 1C:EDT - the user may be working in it (and it holds the workspace lock).
if (Get-Process -Name '1cedt' -ErrorAction SilentlyContinue) {
  Write-Warning "A GUI 1C:EDT (1cedt.exe) is running - nothing to do here. The bridge runs inside the GUI (or close the GUI and toggle again for headless)."
  Done 2
}

$cliAlive = [bool](Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue)
$portAlive = $false
try {
  $s = (Invoke-WebRequest "http://127.0.0.1:$Port/status" -UseBasicParsing -TimeoutSec 3).Content
  $portAlive = $true
} catch {}

if ($cliAlive -or $portAlive) {
  # ---- STOP ----
  Write-Host "edt-bridge headless is RUNNING (1cedtcli: $cliAlive, port ${Port}: $portAlive) - stopping..."
  # Kill the keepalive wrappers first (the bat's cmd + the long ping), then the CLI itself,
  # so nothing keeps the dropins jar locked. Same match as run-headless.ps1's cleanup.
  Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and ($_.CommandLine -match 'edtbridge-headless|999999 127\.0\.0\.1') } |
    ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }
  Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 3
  $still = [bool](Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue)
  if ($still) {
    Write-Warning "1cedtcli is still alive - retry the shortcut, or stop it manually (Task Manager)."
    Done 1
  }
  Write-Host "STOPPED. The workspace is free (a GUI EDT can be launched now)."
  Done 0
}

# ---- START ----
Write-Host "edt-bridge headless is not running - starting (workspace: $Workspace)..."
Write-Host "First model load can take a few minutes; the window reports READY when the server is up."
$runner = Join-Path $PSScriptRoot "run-headless.ps1"
if (-not (Test-Path $runner)) {
  Write-Warning "run-headless.ps1 not found next to this script ($runner)."
  Done 1
}
$runArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner,
             '-Workspace', $Workspace, '-Port', $Port, '-WaitSec', $WaitSec)
if ($EdtDir) { $runArgs += @('-EdtDir', $EdtDir) }
& powershell @runArgs
$code = $LASTEXITCODE
if ($code -eq 0) {
  Write-Host "STARTED. MCP endpoint: http://127.0.0.1:$Port/mcp - toggle again to stop."
} else {
  Write-Warning "Start did not reach READY (exit $code). Check $Workspace\.metadata\.log; the model load may still be running - try /status again in a minute."
}
Done $code
