# Create a desktop shortcut that toggles the edt-bridge headless EDT server (start/stop).
# Run once per machine/user; works from any clone (paths resolve relative to this script).
#
#   powershell -ExecutionPolicy Bypass -File make-shortcut.ps1 -Workspace <ws> [-EdtDir <dir>] [-Name <shortcut name>]
#
# The shortcut runs toggle-headless.ps1 in a visible console (the user sees READY/STOPPED)
# and keeps the window open until Enter, so a double-click always shows the outcome.
# -EdtDir is optional: when omitted, the toggle auto-detects the newest 1C:EDT install each run.
param(
  [Parameter(Mandatory = $true)][string]$Workspace,
  [string]$EdtDir,
  [int]$Port    = 8770,
  [string]$Name = "EDT Bridge headless (start-stop)"
)
$ErrorActionPreference = "Stop"

$toggle = Join-Path $PSScriptRoot "toggle-headless.ps1"
if (-not (Test-Path $toggle)) { throw "toggle-headless.ps1 not found at $toggle" }

$desktop = [Environment]::GetFolderPath("Desktop")
$lnkPath = Join-Path $desktop ($Name + ".lnk")

$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut($lnkPath)
$lnk.TargetPath = (Join-Path $env:SystemRoot "System32\WindowsPowerShell\v1.0\powershell.exe")
$argLine = ('-NoProfile -ExecutionPolicy Bypass -File "{0}" -Workspace "{1}" -Port {2}' -f $toggle, $Workspace, $Port)
if ($EdtDir) { $argLine += (' -EdtDir "{0}"' -f $EdtDir) }
$lnk.Arguments = $argLine
$lnk.WorkingDirectory = $PSScriptRoot
# EDT's own icon when available, else the PowerShell one.
if ($EdtDir) {
  $cli = Join-Path $EdtDir "1cedtcli.exe"
  if (Test-Path $cli) { $lnk.IconLocation = "$cli,0" }
}
$lnk.Description = "Start/stop the edt-bridge headless EDT server (MCP on 127.0.0.1:$Port) without the EDT GUI"
$lnk.Save()

Write-Host "Shortcut created: $lnkPath"
Write-Host "Target workspace: $Workspace"
if ($EdtDir) { Write-Host "EDT dir: $EdtDir (port $Port)" } else { Write-Host "EDT dir: auto-detected at each run (port $Port)" }
