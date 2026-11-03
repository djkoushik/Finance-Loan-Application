@echo off
REM Starts the Finance app (bundled PostgreSQL + web server on http://127.0.0.1:8080)
REM Needs Java 8. Uses JAVA_HOME if it is set, otherwise "java" from the PATH.
cd /d "%~dp0"

set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

set "MVN=mvn"
where mvn >nul 2>nul || if exist "%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn.cmd" set "MVN=%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn.cmd"

REM rebuild when the jar is missing or older than any source file
powershell -NoProfile -Command "if (-not (Test-Path 'target\finance-app-1.0.0.jar')) { exit 1 }; if ((Get-Item 'target\finance-app-1.0.0.jar').Length -lt 50MB) { exit 1 }; $s=(Get-ChildItem src,pom.xml -Recurse -File | Measure-Object LastWriteTime -Maximum).Maximum; if ($s -gt (Get-Item 'target\finance-app-1.0.0.jar').LastWriteTime) { exit 1 }"
if errorlevel 1 (
  echo Building... ^(first build downloads libraries and needs internet; takes a few minutes^)
  call "%MVN%" -q -DskipTests package || (echo Build failed. Check that Java 8 and Maven are installed. & pause & exit /b 1)
)
echo Starting... (first start takes ~30 seconds). To stop: right-click the tray icon and choose Exit.
"%JAVA%" -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -Xverify:none -jar target\finance-app-1.0.0.jar
