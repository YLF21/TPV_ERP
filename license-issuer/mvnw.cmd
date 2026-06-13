@echo off
setlocal
set "BASE_DIR=%~dp0"
set "MAVEN_VERSION=3.9.11"
set "MAVEN_PARENT=%BASE_DIR%.mvn\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_HOME=%MAVEN_PARENT%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
set "ARCHIVE=%MAVEN_PARENT%\apache-maven-%MAVEN_VERSION%-bin.zip"
set "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_CMD%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "New-Item -ItemType Directory -Force -Path '%MAVEN_PARENT%' | Out-Null;" ^
    "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ARCHIVE%';" ^
    "Expand-Archive -Path '%ARCHIVE%' -DestinationPath '%MAVEN_PARENT%' -Force;" ^
    "Remove-Item '%ARCHIVE%'"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_CMD%" -f "%BASE_DIR%pom.xml" %*
exit /b %ERRORLEVEL%
