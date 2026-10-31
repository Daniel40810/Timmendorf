# Legt auf dem Desktop eine Verknuepfung "Timmendorf" an: javaw mit dist\Timmendorf.jar, Arbeitsordner = Projektordner
# (dort liegen db\, templates\ und docs\), Programmsymbol icon\Timmendorf.ico (Timmendorf Phase 19, wie Haithabu).
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jar  = Join-Path $root 'dist\Timmendorf.jar'
$ico  = Join-Path $root 'icon\Timmendorf.ico'

if (-not (Test-Path $jar)) { throw "JAR fehlt: $jar - bitte in NetBeans Clean and Build ausfuehren." }
if (-not (Test-Path $ico)) { throw "ICO fehlt: $ico" }

$javaw = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
    $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
} else {
    $cmd = Get-Command javaw.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javaw = $cmd.Source }
}
if (-not $javaw) { throw "javaw.exe nicht gefunden - JAVA_HOME setzen oder Java in den PATH aufnehmen." }

$desktop = [Environment]::GetFolderPath('Desktop')
$lnk = Join-Path $desktop "Timmendorf.lnk"

$sh = New-Object -ComObject WScript.Shell
$s = $sh.CreateShortcut($lnk)
$s.TargetPath       = $javaw
# JOGL braucht Zugriff auf sun.awt.windows, sonst findet es keine GraphicsConfiguration (steht auch im Manifest des Jars)
$s.Arguments        = '-Xmx6g -Dsun.java2d.uiScale.enabled=true --enable-native-access=ALL-UNNAMED --add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED --add-opens java.desktop/sun.java2d=ALL-UNNAMED -jar "' + $jar + '"'
$s.WorkingDirectory = $root
$s.IconLocation     = $ico
$s.Description      = "Timmendorf - Das Projekt des Segels"
$s.Save()

Write-Host "Verknuepfung angelegt: $lnk"
