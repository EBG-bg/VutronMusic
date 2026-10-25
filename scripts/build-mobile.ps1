[CmdletBinding()]
param([ValidateSet("Debug", "Release")][string] $Configuration = "Debug", [switch] $Offline)
$ErrorActionPreference = "Stop"
$repository = Split-Path -Parent $PSScriptRoot
$app = Join-Path $repository "apps/android-vutron"
Push-Location $app
try {
    if (-not (Test-Path -LiteralPath "node_modules")) {
        & npm.cmd ci --ignore-scripts
        if ($LASTEXITCODE -ne 0) { throw "Mobile dependency installation failed." }
    }
    & node node_modules/typescript/bin/tsc --noEmit
    if ($LASTEXITCODE -ne 0) { throw "Mobile TypeScript check failed." }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "Mobile frontend build failed." }
    & npm.cmd exec -- cap sync android
    if ($LASTEXITCODE -ne 0) { throw "Capacitor sync failed." }
    Push-Location android
    try {
        $arguments = @((":app:assemble" + $Configuration), "--console=plain")
        if ($Offline) { $arguments += "--offline" }
        $savedPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & .\gradlew.bat @arguments
            $gradleExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($gradleExitCode -ne 0) { throw "Android build failed." }
    } finally { Pop-Location }
    Write-Output ("APK output: " + (Join-Path $app ("android/app/build/outputs/apk/" + $Configuration.ToLowerInvariant())))
} finally { Pop-Location }
