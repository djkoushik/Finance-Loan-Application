@echo off
REM Starts the Finance app against the PostgreSQL installed on this PC (run setup-postgres.ps1 once first).
REM Same build logic as start.bat; only the database differs. Web app: http://127.0.0.1:8080
cd /d "%~dp0"
set "SPRING_PROFILES_ACTIVE=postgres"
call start.bat
