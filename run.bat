@echo off
rem Builds the project and starts Tomcat at http://localhost:8080/
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1

if not defined JAVA_HOME (
  for /f "tokens=2 delims==" %%i in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /c:"java.home"') do set "JAVA_HOME=%%i"
)
if defined JAVA_HOME for /f "tokens=* delims= " %%a in ("%JAVA_HOME%") do set "JAVA_HOME=%%a"

set CATALINA_HOME=%CD%\tools\apache-tomcat-10.1.34
set CATALINA_BASE=%CD%\build\tomcat
mkdir "%CATALINA_BASE%\conf" "%CATALINA_BASE%\logs" "%CATALINA_BASE%\temp" "%CATALINA_BASE%\work" "%CATALINA_BASE%\webapps" >nul 2>&1
copy /y "%CATALINA_HOME%\conf\server.xml" "%CATALINA_BASE%\conf\" >nul
copy /y "%CATALINA_HOME%\conf\web.xml" "%CATALINA_BASE%\conf\" >nul
copy /y "%CATALINA_HOME%\conf\logging.properties" "%CATALINA_BASE%\conf\" >nul
copy /y "%CATALINA_HOME%nf	omcat-users.xml" "%CATALINA_BASE%nf\" >nul
copy /y "%CATALINA_HOME%\conf\tomcat-users.xml" "%CATALINA_BASE%\conf\" >nul
xcopy /e /i /q /y build\app "%CATALINA_BASE%\webapps\ROOT" >nul

echo Starting MovieRec on http://localhost:8080/  (Ctrl+C to stop)
call "%CATALINA_HOME%\bin\catalina.bat" run
