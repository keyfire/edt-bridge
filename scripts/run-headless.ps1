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
. (Join-Path $PSScriptRoot "edt-common.ps1")

$EdtDir = Resolve-EdtDir -EdtDir $EdtDir -RequireExe "1cedtcli.exe"

# 1) SAFETY: never disturb a GUI 1C:EDT (1cedt.exe) the user may be working in. What actually
#    collides with one is narrow, so check FOR THE COLLISION rather than for the process:
#      - the port it already serves (two servers cannot share it);
#      - the workspace it holds (Assert-WorkspaceFree below sees the lock);
#      - the dropins directory, shared by every installation - swapping the jar under a running
#        instance is what leaves two jars behind, so that step is skipped while one is up.
#    Refusing outright was worse than it looks: the message told the caller to use a separate
#    workspace and port, and then the script refused that too. Anything left over from a GUI that
#    was closed but has not finished exiting also counted as "running" and blocked the launch.
$guiRunning = [bool](Get-Process -Name '1cedt' -ErrorAction SilentlyContinue)
if ($guiRunning) {
  $portBusy = $false
  try { $portBusy = [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop) } catch {}
  if ($portBusy) {
    Write-Warning "A GUI 1C:EDT is running and port $Port is already served - it is probably its bridge. Close it, or pass -Port <free port> with its own -Workspace."
    exit 2
  }
  Write-Warning "A GUI 1C:EDT is running: leaving its dropins jar alone and starting headless on port $Port. The workspace lock is checked below."
}
# Stop our headless CLI AND its keepalive wrappers (the bat's cmd + the long ping), so repeated
# launches don't leave orphans that keep the old jar locked.
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and ($_.CommandLine -match 'edtbridge-headless|edt_headless|999999 127\.0\.0\.1') } |
  ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop } catch {} }
Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3
# After clearing OUR headless, anything still holding the lock belongs to someone else - stomping it
# would take a live instance down, so stop instead.
if (-not (Assert-WorkspaceFree -Workspace $Workspace)) { exit 2 }
Clear-StaleWorkspaceLock -Workspace $Workspace
# Only when nothing else holds the jar open - see the collision note above.
if (-not $guiRunning) { Set-SingleDropinJar -EdtDir $EdtDir }

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
# The port has to reach the INSTANCE, not just the polling below: the plugin reads it from
# EDT_BRIDGE_PORT (or the settings page), so a -Port that only changed the poll target used to start
# a second server on the default port - straight into the running one.
$portLine = 'set "EDT_BRIDGE_PORT=' + $Port + '"' + "`r`n"
Set-Content -Path $bat -Value ("@echo off`r`n" + $portLine + $tokenLine + $evalLine + $line) -Encoding ASCII
Start-Process -FilePath $bat -WorkingDirectory $EdtDir -WindowStyle Hidden
"Launched headless EDT (1cedtcli). Waiting up to $WaitSec s for the server + model..."

# 3) poll readiness: server up AND at least one project open (model loadable). A workspace with no
#    projects never satisfies the second half - and that is a legitimate state, not a failure: tools
#    that read bundle resources rather than the model work perfectly there. So remember the last
#    status and, at the deadline, tell the two apart instead of calling both NOT READY.
$deadline = (Get-Date).AddSeconds($WaitSec)
$lastStatus = $null
while ((Get-Date) -lt $deadline) {
  try {
    $s = (Invoke-WebRequest "http://127.0.0.1:$Port/status" -UseBasicParsing -TimeoutSec 3).Content
    $lastStatus = $s
    if ($s -match '"openProjects":\["') { "READY: $s"; exit 0 }
  } catch {}
  Start-Sleep -Seconds 5
}
if ($lastStatus) {
  "SERVER UP, no project open in this workspace: $lastStatus"
  exit 0
}
"NOT READY after $WaitSec s (1cedtcli alive: $([bool](Get-Process -Name '1cedtcli' -ErrorAction SilentlyContinue)))"
exit 1
