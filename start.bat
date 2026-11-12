@echo off
REM Starts the Finance app (bundled PostgreSQL + web server on http://127.0.0.1:8080)
REM Needs Java 8 (a JDK is needed for the first build). Uses JAVA_HOME if it is set, otherwise "java" from the PATH.
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

set "MVN="
REM Maven: "mvn.cmd" on the PATH, else MAVEN_HOME (or M2_HOME), else the standard install folder.
REM Always keep the full path: call "mvn.cmd" (quoted, no path) silently fails.
for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MVN set "MVN=%%M"
if not defined MVN if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MVN=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MVN if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" set "MVN=%M2_HOME%\bin\mvn.cmd"
if not defined MVN if exist "%ProgramFiles%\apache-maven-3.9.9\bin\mvn.cmd" set "MVN=%ProgramFiles%\apache-maven-3.9.9\bin\mvn.cmd"
if not defined MVN if exist "%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn.cmd" set "MVN=%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn.cmd"

REM rebuild when the jar is missing or older than any source file
powershell -NoProfile -Command "if (-not (Test-Path 'target\finance-app-1.0.0.jar')) { exit 1 }; if ((Get-Item 'target\finance-app-1.0.0.jar').Length -lt 50MB) { exit 1 }; $s=(Get-ChildItem src,pom.xml -Recurse -File | Measure-Object LastWriteTime -Maximum).Maximum; if ($s -gt (Get-Item 'target\finance-app-1.0.0.jar').LastWriteTime) { exit 1 }"
if errorlevel 1 call :build
if errorlevel 1 exit /b 1

echo Starting... (first start takes ~30 seconds). To stop: right-click the tray icon and choose Exit.
"%JAVA%" -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Xverify:none -jar target\finance-app-1.0.0.jar
exit /b 0

:build
REM A running copy keeps the jar locked, so Maven cannot replace it.
powershell -NoProfile -Command "if (Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*finance-app-1.0.0.jar*' }) { exit 1 }"
if errorlevel 1 (
  echo The Finance app is already running, so it cannot be rebuilt now.
  echo Right-click its tray icon and choose Exit, wait a few seconds, then start this file again.
  pause
  exit /b 1
)
echo Building... (first build downloads libraries and needs internet; takes a few minutes)
REM Maven needs a JDK (with javac), not just a JRE. JAVA_HOME may point at a JRE, so look for a JDK 8.
set "BUILD_JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "BUILD_JDK=%JAVA_HOME%"
if not defined BUILD_JDK for /d %%D in ("%ProgramFiles%\Java\jdk1.8*") do if exist "%%~D\bin\javac.exe" set "BUILD_JDK=%%~D"
if not defined BUILD_JDK for /d %%D in ("%ProgramFiles(x86)%\Java\jdk1.8*") do if exist "%%~D\bin\javac.exe" set "BUILD_JDK=%%~D"
if not defined BUILD_JDK (
  echo No JDK 8 found - only a JRE. Install a JDK 8 and run again, or set JAVA_HOME to it.
  pause
  exit /b 1
)
set "JAVA_HOME=%BUILD_JDK%"
if not defined MVN (
  echo Maven not found. Install Apache Maven or set MAVEN_HOME, then run again.
  pause
  exit /b 1
)
call "%MVN%" -q clean -DskipTests package
if errorlevel 1 (
  echo Build failed. Read the [ERROR] lines above for the reason. Also check that a JDK 8 and Maven are installed.
  pause
  exit /b 1
)
exit /b 0
