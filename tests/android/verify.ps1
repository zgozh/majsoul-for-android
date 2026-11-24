param(
    [Parameter(Mandatory)][string]$DesktopApp,
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Sdk,
    [string]$Serial,
    [ValidateSet('arm64-v8a', 'x86_64')][string]$Abi
)

$ErrorActionPreference = 'Stop'

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with $LASTEXITCODE" }
}

$project = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$work = Join-Path $project 'build\dual-model-check'
$assets = Join-Path $work 'assets'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$api = Join-Path $work 'target-api'
New-Item -ItemType Directory -Force $assets, $classes, $dex, $api | Out-Null

$android = Join-Path $Sdk 'platforms\android-36\android.jar'
$tools = Join-Path $Sdk 'build-tools\36.0.0'
$java = Join-Path $env:JAVA_HOME 'bin'
$manifest = Join-Path $work 'AndroidManifest.xml'
$unsigned = Join-Path $work 'unsigned.apk'
$signed = Join-Path $work 'dual-model-check.apk'
@'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.zheyou.majsoul.dualcheck">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="32" />
    <instrumentation android:name=".DualModelCheck" android:targetPackage="com.zheyou.majsoul" />
</manifest>
'@ | Set-Content -LiteralPath $manifest -Encoding utf8NoBOM

$parity = Join-Path $project 'tests\mortal_parity.py'
Invoke-Checked 'py.exe' @($parity, '--apk', $Apk)
Invoke-Checked (Join-Path $DesktopApp 'runtime\python\python.exe') @(
    $parity, '--desktop', $DesktopApp, '--output', (Join-Path $assets 'mortal-reference.json')
)
# The verified APK supplies the actual interface at runtime. The shim is compiler classpath only.
$scorer = Join-Path $work 'MortalScorer.java'
@'
package com.zheyou.majsoul;
public interface MortalScorer {
    float[] score(float[] observation, boolean[] legal);
}
'@ | Set-Content -LiteralPath $scorer -Encoding utf8NoBOM
Invoke-Checked (Join-Path $java 'javac.exe') @(
    '--release', '17', '-d', $api, $scorer
)
Invoke-Checked (Join-Path $java 'javac.exe') @(
    '--release', '17', '-cp', "$android;$api", '-d', $classes, (Join-Path $PSScriptRoot 'DualModelCheck.java')
)
Invoke-Checked (Join-Path $tools 'd8.bat') @(
    '--lib', $android, '--classpath', $api, '--min-api', '26', '--output', $dex,
    (Join-Path $classes 'com\zheyou\majsoul\dualcheck\DualModelCheck.class')
)
Invoke-Checked (Join-Path $tools 'aapt2.exe') @(
    'link', '-I', $android, '--manifest', $manifest, '-A', $assets, '-o', $unsigned
)
Invoke-Checked (Join-Path $java 'jar.exe') @('uf', $unsigned, '-C', $dex, 'classes.dex')
Invoke-Checked (Join-Path $tools 'apksigner.bat') @(
    'sign', '--ks', (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $signed, $unsigned
)

$adb = Join-Path $Sdk 'platform-tools\adb.exe'
$device = @()
if ($Serial) { $device = @('-s', $Serial) }
$reverse = Invoke-Checked $adb ($device + @('reverse', '--list'))
if ($reverse) { throw 'Run this check with ADB reverse forwarding disabled.' }
$install = @('install', '-r')
if ($Abi) { $install += @('--abi', $Abi) }
Invoke-Checked $adb ($device + $install + @($Apk))
if ($Abi) {
    $package = Invoke-Checked $adb ($device + @('shell', 'dumpsys', 'package', 'com.zheyou.majsoul'))
    if (!($package -match ('primaryCpuAbi=' + [regex]::Escape($Abi)))) {
        throw "Android did not select $Abi"
    }
}
Invoke-Checked $adb ($device + @('install', '-r', $signed))
try {
    $result = Invoke-Checked $adb ($device + @(
        'shell', 'am', 'instrument', '-w', 'com.zheyou.majsoul.dualcheck/.DualModelCheck'
    ))
    $result
    if (!($result -match '^PASS dual-model APK')) { throw 'Dual-model Android check failed.' }
} finally {
    Invoke-Checked $adb ($device + @('uninstall', 'com.zheyou.majsoul.dualcheck'))
}
