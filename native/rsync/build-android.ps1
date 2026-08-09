$ErrorActionPreference = 'Stop'

$RsyncRoot = $PSScriptRoot
$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $RsyncRoot '..\..'))
$Source = Join-Path $RsyncRoot 'src'
$Build = Join-Path $RsyncRoot 'build-android'
$Temp = Join-Path $RsyncRoot 'tmp'
$OutputDir = Join-Path $ProjectRoot 'app\src\main\jniLibs\arm64-v8a'
$Output = Join-Path $OutputDir 'librsync.so'
$Ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { 'D:\Android\Sdk\ndk\26.3.11579264' }
$Toolchain = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$Bash = if ($env:MSYS2_BASH) { $env:MSYS2_BASH } else { 'C:\msys64\usr\bin\bash.exe' }

if (-not (Test-Path -LiteralPath $Bash)) { throw "MSYS2 bash not found: $Bash" }
if (-not (Test-Path -LiteralPath (Join-Path $Source 'configure'))) { throw "rsync source not found: $Source" }
if (-not (Test-Path -LiteralPath (Join-Path $Toolchain 'aarch64-linux-android26-clang.cmd'))) {
    throw "Android NDK compiler not found: $Toolchain"
}

function Convert-ToMsysPath([string]$Path) {
    $escaped = $Path.Replace("'", "'\''")
    $converted = & $Bash -lc "cygpath -u '$escaped'"
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($converted)) {
        throw "Cannot convert path for MSYS2: $Path"
    }
    return $converted.Trim()
}

$SourceU = Convert-ToMsysPath $Source
$BuildU = Convert-ToMsysPath $Build
$TempU = Convert-ToMsysPath $Temp
$OutputU = Convert-ToMsysPath $Output
$ToolchainU = Convert-ToMsysPath $Toolchain

New-Item -ItemType Directory -Force -Path $Build, $Temp, $OutputDir | Out-Null

$Script = @"
set -euo pipefail
export TEMP='$TempU' TMP='$TempU' TMPDIR='$TempU'
export CC='$ToolchainU/aarch64-linux-android26-clang.cmd'
export AR='$ToolchainU/llvm-ar.exe'
export RANLIB='$ToolchainU/llvm-ranlib.exe'
export STRIP='$ToolchainU/llvm-strip.exe'
export CFLAGS='-O2 -fPIE -D_FORTIFY_SOURCE=2'
export LDFLAGS='-pie -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=4096'
cd '$BuildU'
'$SourceU/configure' \
  --build=x86_64-pc-msys \
  --host=aarch64-linux-android \
  --disable-md2man \
  --disable-openssl \
  --disable-xxhash \
  --disable-zstd \
  --disable-lz4 \
  --disable-iconv \
  --disable-ipv6 \
  --disable-roll-simd \
  --with-included-popt \
  --with-included-zlib
make -j4
'$ToolchainU/llvm-strip.exe' --strip-unneeded rsync -o '$OutputU'
"@

$ScriptFile = Join-Path $Temp 'build-rsync.sh'
[System.IO.File]::WriteAllText($ScriptFile, $Script, [System.Text.UTF8Encoding]::new($false))
$ScriptFileU = Convert-ToMsysPath $ScriptFile
& $Bash $ScriptFileU
if ($LASTEXITCODE -ne 0) { throw "rsync compilation failed: $LASTEXITCODE" }

$Hash = (Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash
Write-Host "Built $Output"
Write-Host "SHA-256 $Hash"
