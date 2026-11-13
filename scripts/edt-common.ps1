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
function Get-EdtRank {
  param([System.IO.DirectoryInfo]$Directory, [string]$EdtDir)
  # The build EDT records wins over the name: the launcher keeps a release line in one directory
  # and updates it in place, so "1C_EDT 2026.2" can hold 2026.2.2 next to "1C_EDT (Lite) 2026.2.0".
  $config = Join-Path $EdtDir "configuration\config.ini"
  $recorded = if (Test-Path -LiteralPath $config -PathType Leaf) {
    [regex]::Match((Get-Content -LiteralPath $config -Raw -Encoding UTF8),
      '(?m)^\s*eclipse\.buildId\s*=\s*([0-9]+(?:\.[0-9]+){2})(?:\.([0-9]+))?\s*$')
  }
  # Otherwise rank the version and build in the name separately. The architecture suffix is not part of either.
  $parts = if ($recorded -and $recorded.Success) { $recorded } else {
    [regex]::Match($Directory.Name, '(?<![0-9.])([0-9]+(?:\.[0-9]+){1,3})(?:\+([0-9]+))?(?=$|[-_\s)])')
  }
  [pscustomobject]@{
    Directory = $EdtDir
    Name = $Directory.Name
    Version = $parts.Groups[1].Value -as [version]
    Build = [long]($parts.Groups[2].Value -as [long])
  }
}

<#
.SYNOPSIS
  The bin folder of the JDK to put first on PATH for this EDT, or nothing when EDT finds one itself.
.DESCRIPTION
  An EDT without -vm in 1cedt.ini takes java from PATH, and an older one than its
  -Dosgi.requiredJavaVersion stops the start with a modal dialog. The 1C launcher passes the JDK on
  its own command line, so an EDT it installed has no -vm. Candidates: JAVA_HOME, then the JDK
  components of the 1C installer, the required feature version first.
#>
function Resolve-EdtJava {
  param([Parameter(Mandatory = $true)][string]$EdtDir)
  $ini = Join-Path $EdtDir "1cedt.ini"
  if (-not (Test-Path -LiteralPath $ini -PathType Leaf)) { return }
  $lines = @(Get-Content -LiteralPath $ini -Encoding UTF8 | ForEach-Object { $_.Trim() })
  if ($lines -contains '-vm') { return }
  $required = $lines | ForEach-Object { if ($_ -match '^-Dosgi\.requiredJavaVersion=([0-9]+)$') { [int]$Matches[1] } } |
    Select-Object -First 1
  if (-not $required) { return }
  $major = {
    param([string]$JavaDir)
    $release = Join-Path $JavaDir "release"
    if (-not (Test-Path -LiteralPath $release -PathType Leaf)) { return $null }
    $m = [regex]::Match((Get-Content -LiteralPath $release -Raw), '(?m)^JAVA_VERSION="(?:1\.)?([0-9]+)')
    if ($m.Success) { [int]$m.Groups[1].Value } else { $null }
  }
  $onPath = Get-Command javaw.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($onPath) {
    $current = & $major (Split-Path -Parent (Split-Path -Parent $onPath.Source))
    if ($current -and $current -ge $required) { return }
  }
  if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\javaw.exe"))) {
    $homeMajor = & $major $env:JAVA_HOME
    if ($homeMajor -and $homeMajor -ge $required) { return (Join-Path $env:JAVA_HOME "bin") }
  }
  if (-not $env:ProgramFiles) { return }
  $components = Join-Path $env:ProgramFiles "1C\1CE\components"
  $found = Get-ChildItem -LiteralPath $components -Directory -Filter "*jdk*" -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\javaw.exe") } |
    ForEach-Object { [pscustomobject]@{ Dir = $_; Major = (& $major $_.FullName) } } |
    Where-Object { $_.Major -and $_.Major -ge $required } |
    Sort-Object @{ Expression = { $_.Major -ne $required } }, @{ Expression = { $_.Major }; Descending = $true },
      @{ Expression = { $_.Dir.Name } } |
    Select-Object -First 1
  if ($found) { return (Join-Path $found.Dir.FullName "bin") }
}

function Resolve-EdtDir {
  param([string]$EdtDir, [Parameter(Mandatory = $true)][string]$RequireExe)
  if ($EdtDir) { return $EdtDir }
  $candidates = @(
    if ($env:LOCALAPPDATA) {
      $base = Join-Path $env:LOCALAPPDATA "1C\1cedtstart\installations"
      Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Get-EdtRank $_ (Join-Path $_.FullName "1cedt") } |
        Sort-Object Version, Build, Name -Descending |
        ForEach-Object { $_.Directory }
    }
    if ($env:ProgramFiles) {
      $components = Join-Path $env:ProgramFiles "1C\1CE\components"
      Get-ChildItem -LiteralPath $components -Directory -Filter "1c-edt-*" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^1c-edt-[0-9]' } |
        ForEach-Object { Get-EdtRank $_ $_.FullName } |
        Sort-Object Version, Build, Name -Descending |
        ForEach-Object { $_.Directory }
    }
  )
  $found = $candidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ $RequireExe) -PathType Leaf } |
    Select-Object -First 1
  if (-not $found) { throw "Could not auto-detect 1C:EDT with $RequireExe. Pass -EdtDir <the folder with $RequireExe>." }
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
  Print which 1C:EDT installation the launch uses and which bridge jar lies in its dropins.
.DESCRIPTION
  A machine can carry several installations, and the script picks one itself, while a replaced jar
  goes into the dropins of the one somebody remembered. A change that went into one installation and
  a start from another looked like a build that had not taken effect: the tool answered the old way,
  and only the process list showed why. So the launch names the installation, its dropins, the name
  and time of the jar, the number of jars (two make Equinox load an arbitrary one) and warns when the
  jar built in build/ is newer than the one in dropins.
#>
function Write-EdtSelection {
  param(
    [Parameter(Mandatory = $true)][string]$EdtDir,
    [string]$BuildDir
  )
  $drop = Join-Path $EdtDir "dropins"
  "EDT installation: $EdtDir"
  "dropins:          $drop"
  $jars = @(
    Get-ChildItem $drop -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending
  )
  if ($jars.Count -eq 0) {
    Write-Warning "no bridge jar in $drop - the bridge tools will not be there"
    return
  }
  $newest = $jars[0]
  "bridge jar:       $($newest.Name) ($($newest.LastWriteTime.ToString('yyyy-MM-dd HH:mm')))"
  if ($jars.Count -gt 1) {
    Write-Warning "$($jars.Count) bridge jars in dropins - Equinox loads an arbitrary one; leave a single jar"
  }
  if (-not $BuildDir) { return }
  $built = Get-ChildItem $BuildDir -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($built -and $built.LastWriteTime -gt $newest.LastWriteTime) {
    Write-Warning ("the jar in $BuildDir is newer ($($built.Name), " +
      "$($built.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))) - this installation runs the OLD code; " +
      "start with -FromBuild to install it")
  }
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

<#
.SYNOPSIS
  The bundle version a bridge jar name carries, e.g. 0.42.2.202611121549; $null for another name.
.DESCRIPTION
  The build names the jar after Bundle-Version with its qualifier, and the bridge reports the same
  string as bridgeBundleVersion on /status - which is what tells a fresh build from the old one.
#>
function Get-BridgeJarVersion {
  param([Parameter(Mandatory = $true)][string]$Path)
  $match = [regex]::Match((Split-Path -Leaf $Path), '^io\.github\.keyfire\.edtbridge_(\d+\.\d+\.\d+(?:\.[0-9A-Za-z_-]+)?)\.jar$')
  if ($match.Success) { return $match.Groups[1].Value }
  return $null
}

<#
.SYNOPSIS
  Make the given jar the only bridge jar in dropins; $true when that is what dropins holds afterwards.
.DESCRIPTION
  Call it when EDT is stopped. Windows releases a jar some time after its process exits, so a locked
  jar is retried for -WaitSec seconds. A jar that stays locked is reported with the EDT processes of
  this installation and nothing is copied: a copy next to it would leave two jars behind.
#>
function Install-DropinJar {
  param(
    [Parameter(Mandatory = $true)][string]$EdtDir,
    [Parameter(Mandatory = $true)][string]$Jar,
    [int]$WaitSec = 30
  )
  $drop = Join-Path $EdtDir "dropins"
  New-Item -ItemType Directory -Force -Path $drop | Out-Null
  $source = (Resolve-Path -LiteralPath $Jar).Path
  $name = Split-Path -Leaf $source
  $target = Join-Path $drop $name
  # A jar already in dropins is kept in place and only its neighbours go.
  $inPlace = [string]::Equals([IO.Path]::GetFullPath($source), [IO.Path]::GetFullPath($target),
    [StringComparison]::OrdinalIgnoreCase)
  $deadline = (Get-Date).AddSeconds($WaitSec)
  while ($true) {
    $left = @(
      Get-ChildItem -LiteralPath $drop -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue |
        Where-Object { -not ($inPlace -and $_.Name -ieq $name) } |
        ForEach-Object {
          try { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction Stop } catch {}
          if (Test-Path -LiteralPath $_.FullName) { $_.Name }
        }
    )
    if ($left.Count -eq 0 -or (Get-Date) -ge $deadline) { break }
    Start-Sleep -Seconds 2
  }
  if ($left.Count -gt 0) {
    Write-Warning "cannot remove $($left -join ', ') from $drop - a process still holds it; nothing was copied."
    $holders = @(Get-EdtProcess | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($EdtDir, [StringComparison]::OrdinalIgnoreCase) })
    foreach ($p in $holders) { Write-Warning ("  pid {0}  {1}" -f $p.ProcessId, $p.Name) }
    return $false
  }
  if (-not $inPlace) { Copy-Item -LiteralPath $source -Destination $target -Force }
  $now = @(Get-ChildItem -LiteralPath $drop -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue)
  if ($now.Count -ne 1 -or $now[0].Name -ne $name) {
    Write-Warning "dropins holds $($now.Count) bridge jars after the copy: $(($now | ForEach-Object Name) -join ', ')"
    return $false
  }
  Write-Host "installed jar:    $name"
  return $true
}
