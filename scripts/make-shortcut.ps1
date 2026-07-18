# Create a desktop shortcut that toggles the edt-bridge headless EDT server (start/stop).
# Run once per machine/user; works from any clone (paths resolve relative to this script).
#
#   powershell -ExecutionPolicy Bypass -File make-shortcut.ps1 [-Workspace <ws>] [-EdtDir <dir>] [-Name <shortcut name>]
#
# If -Workspace is omitted, the script asks for it interactively: type a path, or just press Enter
# to use the current 1C:EDT workspace (a running EDT's -data arg, else the 1cedtstart launcher's
# registered workspace). The resolved path is baked into the shortcut's arguments.
# The shortcut runs toggle-headless.ps1 in a visible console (the user sees READY/STOPPED)
# and keeps the window open until Enter, so a double-click always shows the outcome.
# -EdtDir is optional: when omitted, the toggle auto-detects the newest 1C:EDT install each run.
param(
  [string]$Workspace,
  [string]$EdtDir,
  [int]$Port    = 8770,
  [string]$Name = "EDT Bridge headless (start-stop)"
)
$ErrorActionPreference = "Stop"

# The current 1C:EDT workspace: a running EDT's -data argument if one is up, else the 1cedtstart
# launcher's registered workspace (projects.json). $null when nothing can be detected.
function Get-CurrentWorkspace {
  foreach ($proc in @('1cedtcli', '1cedt')) {
    foreach ($inst in (Get-CimInstance Win32_Process -Filter "Name='$proc.exe'" -ErrorAction SilentlyContinue)) {
      if ($inst.CommandLine -match '-data\s+"?([^"]+?)"?(?:\s|$)') {
        $ws = $Matches[1].Trim()
        if ($ws -and (Test-Path $ws)) { return $ws }
      }
    }
  }
  $projJson = Join-Path $env:LOCALAPPDATA "1C\1cedtstart\projects.json"
  if (Test-Path $projJson) {
    try {
      $loc = (Get-Content $projJson -Raw -Encoding UTF8 | ConvertFrom-Json).data |
        Where-Object { $_.location } | Select-Object -First 1 -ExpandProperty location
      if ($loc -and (Test-Path $loc)) { return $loc }
    } catch {}
  }
  return $null
}

# Resolve the workspace: use -Workspace if given, otherwise prompt (example + Enter = current).
if (-not $Workspace) {
  $current = Get-CurrentWorkspace
  Write-Host "Enter the 1C:EDT workspace this shortcut should manage."
  Write-Host "  example: D:\1C\1cedtstart\projects\SM"
  if ($current) {
    Write-Host "  or press Enter to use the current one: $current"
  } else {
    Write-Host "  (no current workspace detected - a path is required)"
  }
  $answer = Read-Host "Workspace"
  if ($answer -and $answer.Trim()) {
    $Workspace = $answer.Trim()
  } elseif ($current) {
    $Workspace = $current
  } else {
    throw "No workspace given and none could be detected. Re-run with -Workspace <path>."
  }
}
if (-not (Test-Path $Workspace)) {
  Write-Warning "Workspace path does not exist yet: $Workspace (creating the shortcut anyway)."
}

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
