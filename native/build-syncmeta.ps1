$ErrorActionPreference = 'Stop'
$Ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { 'D:\Android\Sdk\ndk\26.3.11579264' }
$Compiler = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd'
$Source = Join-Path $PSScriptRoot 'syncmeta\syncmeta.cpp'
$OutputDir = Join-Path $PSScriptRoot '..\app\src\main\jniLibs\arm64-v8a'
$Output = Join-Path $OutputDir 'libsyncmeta.so'

if (-not (Test-Path $Compiler)) { throw "NDK compiler not found: $Compiler" }
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
& $Compiler -std=c++17 -O2 -fPIE -pie -static-libstdc++ `
    '-Wl,-z,max-page-size=16384' '-Wl,-z,common-page-size=4096' `
    -Wall -Wextra -Werror $Source -o $Output
if ($LASTEXITCODE -ne 0) { throw "syncmeta compilation failed: $LASTEXITCODE" }
Write-Host "Built $Output"
