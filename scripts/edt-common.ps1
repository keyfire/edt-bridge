# Shared helpers for the edt-bridge launcher scripts. Dot-source it:
#
#   . (Join-Path $PSScriptRoot "edt-common.ps1")
#
# The point of this file is that both launchers agree on ONE answer to "is an EDT already using
# this workspace?". Getting that wrong is expensive: a second EDT on the same workspace dies with
# "workspace is already in use by another application", and a launcher that force-clears the lock
# can take a running instance down with it.


<#
.SYNOPSIS
  Resolve the 1C:EDT installation directory, newest first.
.PARAMETER EdtDir
  Explicit directory; returned as-is when given.
.PARAMETER RequireExe
  Executable that must exist in the directory - '1cedt.exe' for the GUI, '1cedtcli.exe' for the CLI.
#>
function Resolve-EdtDir {
  param([string]$EdtDir, [Parameter(Mandatory = $true)][string]$RequireExe)
  if ($EdtDir) { return $EdtDir }
  $base = Join-Path $env:LOCALAPPDATA "1C\1cedtstart\installations"
  $found = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName "1cedt" } |
    Where-Object { Test-Path (Join-Path $_ $RequireExe) } |
    Select-Object -First 1
  if (-not $found) { throw "Could not auto-detect 1C:EDT with $RequireExe under $base; pass -EdtDir <...\1cedt>." }
  return $found
}

<#
.SYNOPSIS
  Whether the workspace lock is actually held by a live process.
.DESCRIPTION
  This is the authoritative check - the same file Eclipse itself locks, so it stays right no matter
  how the other instance was started. A leftover .lock from a killed process is NOT held and opens
  fine; that is the difference between "someone is working here" and "something crashed here".
#>
function Test-WorkspaceLocked {
  param([Parameter(Mandatory = $true)][string]$Workspace)
  $lock = Join-Path $Workspace ".metadata\.lock"
  if (-not (Test-Path $lock)) { return $false }
  try {
    $fs = [System.IO.File]::Open($lock, 'Open', 'ReadWrite', 'None')
    $fs.Close()
    return $false          # opened exclusively - nobody holds it
  } catch {
    return $true           # in use
  }
}

<#
.SYNOPSIS
  Running 1C:EDT processes, optionally only those started on a given workspace.
#>
function Get-EdtProcess {
  param([string]$Workspace)
  # The 1C:EDT executables: the GUI, the CLI, and the console launcher the CLI spawns. Kept local -
  # a script-scoped variable does not survive dot-sourcing into every caller's scope.
  $names = @('1cedt', '1cedtcli', '1cedtc')
  $out = @()
  foreach ($p in (Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
    if (-not $p.Name) { continue }
    if ($names -notcontains [IO.Path]::GetFileNameWithoutExtension($p.Name)) { continue }
    # Contains(), not -like: a path is full of characters -like would read as wildcards.
    if ($Workspace -and -not ($p.CommandLine -and $p.CommandLine.Contains($Workspace))) { continue }
    $out += $p
  }
  return ,$out
}

<#
.SYNOPSIS
  Refuse to start when the workspace is already in use; returns $true when it is safe to proceed.
.DESCRIPTION
  Checks the lock first (authoritative), then reports which processes look responsible so the
  message is actionable rather than just "busy".
#>
function Assert-WorkspaceFree {
  param([Parameter(Mandatory = $true)][string]$Workspace)
  if (-not (Test-WorkspaceLocked -Workspace $Workspace)) { return $true }
  Write-Warning "Workspace '$Workspace' is already in use - refusing to start a second EDT on it."
  $owners = @(Get-EdtProcess -Workspace $Workspace)
  $attributed = $owners.Count -gt 0
  if (-not $attributed) {
    # A workspace opened from EDT's own switcher carries no -data on the command line, so failing to
    # attribute it proves nothing - show every EDT instead of claiming there is none.
    $owners = @(Get-EdtProcess)
  }
  if ($owners.Count -gt 0) {
    Write-Warning $(if ($attributed) { "Holding it:" } else { "Running EDT processes (one of these holds it):" })
    foreach ($p in $owners) { Write-Warning ("  pid {0}  {1}" -f $p.ProcessId, $p.Name) }
    Write-Warning "Close that EDT, or start on a different -Workspace."
  } else {
    Write-Warning "No EDT process is running - the lock may belong to another user or be stuck; remove it by hand if you are sure."
  }
  return $false
}

<#
.SYNOPSIS
  Remove a workspace lock left behind by a killed process. Never touches a lock that is held.
#>
function Clear-StaleWorkspaceLock {
  param([Parameter(Mandatory = $true)][string]$Workspace)
  $lock = Join-Path $Workspace ".metadata\.lock"
  if (-not (Test-Path $lock)) { return }
  if (Test-WorkspaceLocked -Workspace $Workspace) { return }
  try { Remove-Item $lock -Force -ErrorAction Stop } catch {}
}

<#
.SYNOPSIS
  Keep exactly one edt-bridge jar in dropins - the newest by name.
.DESCRIPTION
  Two singletons of the same bundle make Equinox resolve an arbitrary (often older) one, so a fresh
  build silently does not take effect.
#>
function Set-SingleDropinJar {
  param([Parameter(Mandatory = $true)][string]$EdtDir)
  $drop = Join-Path $EdtDir "dropins"
  Get-ChildItem $drop -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -Skip 1 |
    ForEach-Object { try { Remove-Item $_.FullName -Force -ErrorAction Stop } catch {} }
}
