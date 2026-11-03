# Builds a self-contained Windows package: dist\FinanceApp (+ FinanceApp.zip) with its own Java runtime.
# Usage:  powershell -ExecutionPolicy Bypass -File package.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\BellSoft\LibericaJDK-8-Full" }
$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { "mvn" } else { "$env:USERPROFILE\tools\apache-maven-3.9.9\bin\mvn.cmd" }
$env:JAVA_HOME = $jdk

Push-Location $root
& $mvn -q -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$dist = "$root\dist\FinanceApp"
Remove-Item "$root\dist" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item "$root\target\finance-app-1.0.0.jar" "$dist\finance-app.jar"
Copy-Item "$jdk\jre" "$dist\jre" -Recurse
Copy-Item "$root\finance.ico" "$dist\finance.ico"

# Ready-made database (initdb + migrations already done, PostgreSQL files already unpacked). On a new PC the app copies
# this instead of running initdb / unpacking ~1400 files, which takes 1-2 minutes on a slow disk with antivirus.
$tplBuild = Join-Path $env:TEMP "finance-pgtemplate-build"
Remove-Item $tplBuild -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $tplBuild | Out-Null
$dbPort = 54999; $webPort = 18999
$app = Start-Process "$dist\jre\bin\java.exe" -PassThru -WindowStyle Hidden -ArgumentList @(
    "-Xmx512m", "-jar", "`"$dist\finance-app.jar`"", "--finance.open-window=false", "--server.port=$webPort",
    "--finance.db-port=$dbPort", "--finance.data-dir=$tplBuild", "--finance.photo-dir=$tplBuild\photos", "--finance.backup-dir=$tplBuild\backups")
$deadline = (Get-Date).AddMinutes(5)
$up = $false
while ((Get-Date) -lt $deadline -and -not $up -and -not $app.HasExited) {
    try { $c = New-Object Net.Sockets.TcpClient; $c.Connect("127.0.0.1", $webPort); $c.Close(); $up = $true } catch { Start-Sleep -Milliseconds 500 }
}
if (-not $up) { throw "Could not build the database template (application did not start)" }
$pgctl = Get-ChildItem "$tplBuild\pgruntime" -Recurse -Filter pg_ctl.exe | Select-Object -First 1
& $pgctl.FullName stop -D "$tplBuild\pgdata" -m fast -w | Out-Null   # clean shutdown: no WAL replay on first start
Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Remove-Item "$tplBuild\pgdata\postmaster.pid", "$tplBuild\pgdata\epg-lock" -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$dist\pgtemplate" | Out-Null
Copy-Item "$tplBuild\pgdata" "$dist\pgtemplate\pgdata" -Recurse
Copy-Item "$tplBuild\pgruntime" "$dist\pgtemplate\pgruntime" -Recurse
Remove-Item $tplBuild -Recurse -Force -ErrorAction SilentlyContinue

@'
@echo off
REM Starts Sri Raja Rajeshwari Finance. Data is kept in %LOCALAPPDATA%\SriRajaRajeshwariFinance
set "APPDATA_DIR=%LOCALAPPDATA%\SriRajaRajeshwariFinance"
if not exist "%APPDATA_DIR%" mkdir "%APPDATA_DIR%"
cd /d "%~dp0"
start "" "%~dp0jre\bin\javaw.exe" -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Xverify:none -jar "%~dp0finance-app.jar" --finance.pg-template-dir="%~dp0pgtemplate" --finance.data-dir="%APPDATA_DIR%\data"--finance.photo-dir="%APPDATA_DIR%\photos" --finance.backup-dir="%APPDATA_DIR%\backups" --logging.file.name="%APPDATA_DIR%\app.log"
'@ | Set-Content "$dist\Finance.bat" -Encoding ascii

@'
@echo off
REM Creates a desktop shortcut that starts the application without a console window.
set "TARGET=%~dp0Finance.bat"
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\Sri Raja Rajeshwari Finance.lnk'); $s.TargetPath='%TARGET%'; $s.WorkingDirectory='%~dp0'; $s.IconLocation='%~dp0finance.ico'; $s.WindowStyle=7; $s.Save()"
echo Shortcut created on the desktop.
pause
'@ | Set-Content "$dist\Create-Desktop-Shortcut.bat" -Encoding ascii

@'
SRI RAJA RAJESHWARI FINANCE
===========================
1. Copy this whole folder anywhere (for example C:\FinanceApp).
2. Double-click Create-Desktop-Shortcut.bat once, then start the app from the desktop shortcut
   (or double-click Finance.bat).
3. First start takes about 30 seconds (a "please wait" window is shown meanwhile); later starts are faster. The application opens in its own window and an icon appears
   in the system tray: right-click it and choose Exit to stop the application.
4. First login on a brand-new installation: user "admin", password "admin123" - change it under
   Administration > Change Password.

Your data is stored in %LOCALAPPDATA%\SriRajaRajeshwariFinance. Use Administration > Backup & Restore
regularly and keep the downloaded zip files somewhere safe.
'@ | Set-Content "$dist\README.txt" -Encoding ascii

Compress-Archive -Path $dist -DestinationPath "$root\dist\FinanceApp.zip" -Force
Pop-Location
Get-Item "$root\dist\FinanceApp.zip" | Select Name, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}




