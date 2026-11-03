@echo off
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
java -cp build\app\WEB-INF\classes;build\test movierec.RecommenderTest webapp\WEB-INF\data\movies.txt || exit /b 1
java -cp build\app\WEB-INF\classes;build\test movierec.ApiSearchTest webapp\WEB-INF\data\movies.txt || exit /b 1
