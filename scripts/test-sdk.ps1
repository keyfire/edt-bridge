# Run regression tests against the installed EDT SDK and an already compiled bridge bundle.
# Build the bundle first. Supply a JDK new enough to read the installed SDK class files.
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$Pool = (Join-Path $env:USERPROFILE '.p2\pool\plugins'),
    [string]$Junit = '',
    [string]$Classes = '',
    [string]$Output = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not $Junit) { $Junit = Join-Path $root 'build\tools\junit-platform-console-standalone-1.11.4.jar' }
if (-not $Classes) { $Classes = Join-Path $root 'build\bin' }
if (-not $Output) {
    $Output = Join-Path ([System.IO.Path]::GetTempPath()) ('edt-bridge-sdk-tests-' + [guid]::NewGuid().ToString('N'))
}
if (-not (Test-Path -LiteralPath $Junit)) { throw 'JUnit not found. Run scripts/test-java.sh first or pass -Junit.' }
if (-not (Test-Path -LiteralPath $Classes)) { throw 'Compiled bridge classes not found. Build the bundle first or pass -Classes.' }
if (-not (Test-Path -LiteralPath $Pool)) { throw 'EDT bundle pool not found. Pass -Pool.' }
if ($JdkHome) {
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    $java = Join-Path $JdkHome 'bin\java.exe'
} else {
    $javac = (Get-Command javac -ErrorAction Stop).Source
    $java = Join-Path (Split-Path -Parent $javac) 'java.exe'
}
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'tests\sdk') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$classpath = "$Output;$Classes;$Junit;$Pool\*"
& $javac --release 17 -encoding UTF-8 -cp $classpath -d $Output @sources
if ($LASTEXITCODE -ne 0) { throw "SDK test compilation failed. Check -JdkHome. Output: $Output" }
& $java -cp $classpath org.junit.platform.console.ConsoleLauncher execute --scan-class-path $Output --details=summary --disable-banner
if ($LASTEXITCODE -ne 0) { throw "SDK regression tests failed. Output: $Output" }
