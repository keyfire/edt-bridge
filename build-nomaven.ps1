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
$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$bundle = Join-Path $root "io.github.keyfire.edtbridge"
$src    = Join-Path $bundle "src"
$out    = Join-Path $root "build"
$bin    = Join-Path $out "bin"

if (-not $JdkHome) { throw "Set -JdkHome <path> or the JAVA_HOME env var to a JDK 17+ home." }
$javac  = Join-Path $JdkHome "bin\javac.exe"
$jarexe = Join-Path $JdkHome "bin\jar.exe"
if (-not (Test-Path $javac)) { throw "javac not found: $javac (need a JDK 17+)" }
if (-not (Test-Path $Pool))  { throw "EDT p2 pool not found: $Pool (point -Pool at <your-home>\.p2\pool\plugins)" }

# Resolve compile-time bundles from the pool (latest match each).
$need = @(
  'org.eclipse.osgi',
  'org.eclipse.equinox.common',
  'org.eclipse.core.resources',
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
