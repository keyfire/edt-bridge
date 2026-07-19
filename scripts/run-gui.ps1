# Start the 1C:EDT GUI on a workspace, refusing when one is already using it.
#
#   powershell -ExecutionPolicy Bypass -File run-gui.ps1 -Workspace <ws> [-EdtDir <dir>]
#
# Why this exists rather than just running 1cedt.exe: starting a second EDT on a workspace that is
# already open fails with "workspace is already in use by another application" - and if you do it
# twice in a row without noticing, you end up with a pile of half-started instances and an error
# dialog per instance. The check below is the one thing this script adds, and it is the whole point.
#
# Unlike run-headless.ps1 this script never kills anything. A GUI EDT may have unsaved work in it,
# so the only safe answer to "one is already running" is to say so and stop.
param(
  [Parameter(Mandatory = $true)][string]$Workspace,
  [string]$EdtDir,
  [int]$Port = 8770
)
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "edt-common.ps1")

$EdtDir = Resolve-EdtDir -EdtDir $EdtDir -RequireExe "1cedt.exe"
$exe = Join-Path $EdtDir "1cedt.exe"

if (-not (Assert-WorkspaceFree -Workspace $Workspace)) { exit 2 }

# A lock file with no owner is debris from a killed instance - EDT would refuse to start on it.
Clear-StaleWorkspaceLock -Workspace $Workspace
Set-SingleDropinJar -EdtDir $EdtDir

Start-Process -FilePath $exe -ArgumentList '-data', $Workspace
"Launched 1C:EDT GUI on $Workspace"
"The bridge answers on http://127.0.0.1:$Port/mcp once the workbench and the projects have loaded."
exit 0
