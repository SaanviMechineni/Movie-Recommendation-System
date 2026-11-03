@echo off
rem Compiles the Java sources and assembles the web app into build\app
setlocal
cd /d "%~dp0"
set TOMCAT=tools\apache-tomcat-10.1.34
if not exist "%TOMCAT%\lib\servlet-api.jar" (
  echo Tomcat not found in %TOMCAT%. See README.md.
  exit /b 1
)

if exist build rmdir /s /q build
mkdir build\app\WEB-INF\classes build\test

dir /s /b src\main\java\*.java > build\main-sources.txt
javac -encoding UTF-8 -cp "%TOMCAT%\lib\servlet-api.jar" -d build\app\WEB-INF\classes @build\main-sources.txt
if errorlevel 1 (echo COMPILE FAILED & exit /b 1)

dir /s /b src\test\java\*.java > build\test-sources.txt
javac -encoding UTF-8 -cp build\app\WEB-INF\classes -d build\test @build\test-sources.txt
if errorlevel 1 (echo TEST COMPILE FAILED & exit /b 1)

xcopy /e /i /q /y webapp build\app >nul
echo BUILD OK
