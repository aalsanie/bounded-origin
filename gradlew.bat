@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "WRAPPER_URL=https://raw.githubusercontent.com/gradle/gradle/v9.7.1/gradle/wrapper/gradle-wrapper.jar"
set "WRAPPER_SHA256=7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"

if not exist "%WRAPPER_JAR%" (
  powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri '%WRAPPER_URL%' -OutFile '%WRAPPER_JAR%.tmp'"
  if errorlevel 1 exit /b 1
  for /f "tokens=*" %%H in ('powershell -NoProfile -NonInteractive -Command "(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%.tmp').Hash.ToLowerInvariant()"') do set "ACTUAL_SHA256=%%H"
  if /I not "%ACTUAL_SHA256%"=="%WRAPPER_SHA256%" (
    del /q "%WRAPPER_JAR%.tmp" 2>nul
    echo Gradle wrapper JAR checksum mismatch. 1>&2
    exit /b 1
  )
  move /y "%WRAPPER_JAR%.tmp" "%WRAPPER_JAR%" >nul
)

for /f "tokens=*" %%H in ('powershell -NoProfile -NonInteractive -Command "(Get-FileHash -Algorithm SHA256 '%WRAPPER_JAR%').Hash.ToLowerInvariant()"') do set "ACTUAL_SHA256=%%H"
if /I not "%ACTUAL_SHA256%"=="%WRAPPER_SHA256%" (
  echo Gradle wrapper JAR checksum mismatch. 1>&2
  exit /b 1
)

if defined JAVA_HOME (
  set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVACMD=java.exe"
)

"%JAVACMD%" -Dfile.encoding=UTF-8 -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
