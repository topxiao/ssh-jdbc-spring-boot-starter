@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "BASH_EXE="

if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%LocalAppData%\Programs\Git\bin\bash.exe" set "BASH_EXE=%LocalAppData%\Programs\Git\bin\bash.exe"
if not defined BASH_EXE for /f "delims=" %%B in ('where bash 2^>nul') do if not defined BASH_EXE set "BASH_EXE=%%B"

if not defined BASH_EXE (
    echo [ERROR] Git Bash was not found. Install Git for Windows or run release.sh from Git Bash.
    endlocal
    exit /b 1
)

"%BASH_EXE%" "%SCRIPT_DIR%release.sh" %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
