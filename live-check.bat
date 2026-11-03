@echo off
rem Checks the search against the REAL TMDB API (needs your key in movie-api.properties).
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
java -cp build\app\WEB-INF\classes;build\test movierec.LiveSearchCheck webapp\WEB-INF\data\movies.txt
