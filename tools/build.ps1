param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Tasks = @(':app:testDebugUnitTest', ':app:assembleRelease'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
$previousJvmOptions = $env:JAVA_TOOL_OPTIONS
try {
    $socketDir = Join-Path $projectRoot '.gradle/sockets'
    New-Item -ItemType Directory -Force $socketDir | Out-Null
    $env:JAVA_TOOL_OPTIONS = ($previousJvmOptions + ' -Djdk.net.unixdomain.tmpdir="' + $socketDir + '"').Trim()
    if (!$env:JAVA_HOME -and (Test-Path "$env:USERPROFILE/.qoder/toolchain/jdk/bin/java.exe")) { $env:JAVA_HOME = "$env:USERPROFILE/.qoder/toolchain/jdk" }
    if (!$env:ANDROID_HOME -and !$env:ANDROID_SDK_ROOT -and !(Test-Path local.properties)) {
        foreach ($candidate in @("$env:LOCALAPPDATA/Android/Sdk", "$env:USERPROFILE/.qoder/toolchain/android-sdk")) {
            if (Test-Path "$candidate/platforms") { $env:ANDROID_HOME = $candidate; break }
        }
    }
    & './gradlew.bat' --console=plain @Tasks
    exit $LASTEXITCODE
} finally { $env:JAVA_TOOL_OPTIONS = $previousJvmOptions; Pop-Location }
