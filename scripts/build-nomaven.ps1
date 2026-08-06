# edt-bridge - no-Maven build.
# Compiles + packages the OSGi bundle jar using a local JDK and the local 1C:EDT p2 bundle pool.
# No network, no install. (For the standard build use Maven + Tycho: pom.xml.)
# It does NOT install the bundle into EDT - copy the built jar to <EDT>\dropins\ and restart EDT.
#
# Defaults assume a typical install; override -Pool / -JdkHome if yours differ.
param(
  [string]$Pool    = (Join-Path $env:USERPROFILE ".p2\pool\plugins"),
  [string]$JdkHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "" }),
  [switch]$Dist    # also place the jar into dist/ (the release asset published by the GitHub workflow)
)
$ErrorActionPreference = "Stop"
$root   = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$bundle = Join-Path $root "io.github.keyfire.edtbridge"
$src    = Join-Path $bundle "src"
$out    = Join-Path $root "build"
$bin    = Join-Path $out "bin"

if (-not (Test-Path $Pool))  { throw "EDT p2 pool not found: $Pool (point -Pool at <your-home>\.p2\pool\plugins)" }

# The JDK must be new enough to READ the EDT bundles: EDT 2026.2 ships Java 25 class files
# (major 69), which a JDK 17 javac rejects outright ("class file has wrong version 69.0,
# should be 61.0"). The requirement is read from the pool itself, so this keeps working when
# EDT moves to a newer Java. The bundle bytecode stays at Java 17 (see --release below).
function Get-JavacJavaLevel([string]$javacPath) {
  if (-not (Test-Path $javacPath)) { return 0 }
  $text = (& $javacPath -version 2>&1) -join " "
  if ($text -match 'javac\s+(\d+)') { return [int]$Matches[1] }
  return 0
}
function Get-PoolJavaLevel([string]$poolPath) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $probe = Get-ChildItem $poolPath -Filter "com._1c.g5.v8.dt.core_*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.BaseName -match "^com\._1c\.g5\.v8\.dt\.core_\d" } |
           Sort-Object Name -Descending | Select-Object -First 1
  if (-not $probe) { return 0 }
  $zip = [System.IO.Compression.ZipFile]::OpenRead($probe.FullName)
  try {
    $entry = $zip.Entries | Where-Object { $_.FullName -like "*.class" } | Select-Object -First 1
    if (-not $entry) { return 0 }
    $stream = $entry.Open()
    $head = New-Object byte[] 8
    [void]$stream.Read($head, 0, 8)
    $stream.Close()
    return ([int]$head[6] * 256 + [int]$head[7]) - 44   # class-file major 61 = Java 17
  } finally { $zip.Dispose() }
}
function Find-JdkAtLeast([int]$level) {
  # JDKs shipped by the 1C:Enterprise installer alongside EDT come first - they always match
  # the EDT being built against; then the usual vendor roots.
  $roots = @(
    (Join-Path $env:ProgramFiles "1C\1CE\components"),
    (Join-Path $env:ProgramFiles "Java"),
    (Join-Path $env:ProgramFiles "Eclipse Adoptium"),
    (Join-Path $env:ProgramFiles "Microsoft\jdk"),
    (Join-Path $env:ProgramFiles "BellSoft"),
    (Join-Path $env:ProgramFiles "Zulu"),
    (Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium")
  )
  foreach ($r in $roots) {
    if (-not (Test-Path $r)) { continue }
    foreach ($d in (Get-ChildItem $r -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)) {
      if ((Get-JavacJavaLevel (Join-Path $d.FullName "bin\javac.exe")) -ge $level) { return $d.FullName }
    }
  }
  return ""
}

$poolLevel = Get-PoolJavaLevel $Pool
if ($poolLevel -lt 17) { $poolLevel = 17 }   # unreadable pool probe - fall back to the old floor
if ($JdkHome -and (Get-JavacJavaLevel (Join-Path $JdkHome "bin\javac.exe")) -lt $poolLevel) {
  "JDK $JdkHome cannot read Java $poolLevel bundles - looking for a newer one ..."
  $JdkHome = ""
}
if (-not $JdkHome) { $JdkHome = Find-JdkAtLeast $poolLevel }
if (-not $JdkHome) {
  throw "The EDT bundles in $Pool are Java $poolLevel class files, so JDK $poolLevel+ is needed to compile against them. Pass -JdkHome <path to a JDK $poolLevel> or point JAVA_HOME at one."
}
"JDK: $JdkHome (pool needs Java $poolLevel)"
$javac  = Join-Path $JdkHome "bin\javac.exe"
$jarexe = Join-Path $JdkHome "bin\jar.exe"
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

# Resolve compile-time bundles from the pool (latest match each).
$need = @(
  'org.eclipse.osgi',
  'org.eclipse.equinox.common',
  'org.eclipse.core.resources',
  'org.eclipse.core.filebuffers',
  'org.eclipse.text',
  'org.eclipse.core.runtime',
  'org.eclipse.core.jobs',
  'org.eclipse.ui.workbench',
  'org.eclipse.ui',
  'org.eclipse.jface',
  'org.eclipse.core.commands',
  'org.eclipse.equinox.preferences',
  'org.osgi.service.prefs',
  'com.google.gson',
  'org.eclipse.emf.ecore',
  'org.eclipse.emf.common',
  'org.eclipse.swt.win32.win32.x86_64',  # compile vs the SWT fragment (host org.eclipse.swt is a stub); runtime Require-Bundle uses the host
  'org.eclipse.xtext',
  'org.eclipse.xtext.util',
  'com.google.inject',
  'com.google.guava',
  'com._1c.g5.wiring',
  'com._1c.g5.v8.dt.core',
  'com._1c.g5.v8.dt.metadata',
  'com._1c.g5.v8.dt.md.extension',
  'com._1c.g5.v8.dt.mcore',
  'com._1c.g5.v8.dt.bsl.model',
  'com._1c.g5.v8.dt.bsl',
  'com._1c.g5.v8.dt.form.model',
  'com._1c.g5.v8.dt.form',
  'com._1c.g5.v8.dt.form.layout',
  'com._1c.g5.v8.dt.form.layout.model',
  'com._1c.g5.v8.dt.form.presentation',
  'com._1c.g5.v8.dt.dcs.model',
  'com._1c.g5.v8.dt.platform',
  'com._1c.g5.v8.dt.export',
  'com._1c.g5.v8.dt.platform.services.core',
  'com._1c.g5.v8.dt.platform.services.model',
  'com._1c.g5.designer.ssh.client',
  'com._1c.g5.v8.dt.validation',
  'com._1c.g5.v8.dt.refactoring.core',
  'com._1c.g5.v8.dt.md.refactoring',
  'com._1c.g5.v8.bm.core',
  'com._1c.g5.v8.bm.integration',
  'org.eclipse.debug.core',
  'com._1c.g5.v8.dt.debug.core',
  'com._1c.g5.v8.dt.debug.model'
)
"Classpath bundles:"
$cp = @()
foreach ($n in $need) {
  # Require a digit right after "<bundle-id>_" so version-specific siblings (e.g.
  # com._1c.g5.v8.dt.platform_v8.3.27, which sorts above the real platform_12.x) are excluded.
  $rx = "^" + [regex]::Escape($n) + "_\d"
  $j = Get-ChildItem $Pool -Filter "$($n)_*.jar" -ErrorAction SilentlyContinue |
       Where-Object { $_.BaseName -match $rx } |
       Sort-Object Name -Descending | Select-Object -First 1
  if ($j) { $cp += $j.FullName; "  + $($j.Name)" } else { "  ! MISSING: $n" }
}
$cpStr = ($cp -join ';')

# Clean + compile.
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $bin | Out-Null
$sources = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
# --release 17 regardless of the JDK used: one jar then loads in every supported EDT, including
# older ones that still run on Java 17. Raise it only when the oldest supported EDT does.
"Compiling $($sources.Count) sources with --release 17 ..."
& $javac --release 17 -encoding UTF-8 -cp $cpStr -d $bin @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed (exit $LASTEXITCODE)" }
"OK: compiled."

# Package the bundle jar (concrete version instead of .qualifier).
Copy-Item (Join-Path $bundle "plugin.xml") $bin -Force
# The DS component descriptor (Service-Component in the manifest) must be inside the jar, or the
# lazy bundle never activates headless and the MCP server never starts. Tycho includes it via
# build.properties bin.includes; mirror that here.
Copy-Item (Join-Path $bundle "OSGI-INF") $bin -Recurse -Force
# icons/ holds the preference-page info image; include it in the jar (build.properties bin.includes).
$iconsDir = Join-Path $bundle "icons"
if (Test-Path $iconsDir) { Copy-Item $iconsDir $bin -Recurse -Force }
$ts = Get-Date -Format "yyyyMMddHHmm"
$mfRaw = Get-Content (Join-Path $bundle "META-INF\MANIFEST.MF") -Raw
# Base version comes from the manifest's Bundle-Version (X.Y.Z.qualifier), so a version bump
# there flows into the jar name; .qualifier is replaced with the build timestamp.
$baseVer = if ($mfRaw -match 'Bundle-Version:\s*(\d+\.\d+\.\d+)\.qualifier') { $Matches[1] } else { '0.0.1' }
$mf = $mfRaw -replace ([regex]::Escape("$baseVer.qualifier")), "$baseVer.$ts"
$mfTmp = Join-Path $out "MANIFEST.MF"
Set-Content -Path $mfTmp -Value $mf -Encoding ASCII
$jarPath = Join-Path $out "io.github.keyfire.edtbridge_$baseVer.$ts.jar"
& $jarexe cfm $jarPath $mfTmp -C $bin .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)" }
"BUILT: $jarPath"
if ($Dist) {
  # dist/ holds exactly one jar - the release asset the GitHub workflow publishes on a tag.
  $distDir = Join-Path $root "dist"
  New-Item -ItemType Directory -Force -Path $distDir | Out-Null
  Get-ChildItem $distDir -Filter "io.github.keyfire.edtbridge_*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
  Copy-Item $jarPath $distDir
  "DIST: $(Join-Path $distDir (Split-Path $jarPath -Leaf)) - commit it, tag vX.Y.Z, push the tag to release."
}
"Install: copy to <EDT>\dropins\ and restart EDT, then curl http://127.0.0.1:8770/mcp"
