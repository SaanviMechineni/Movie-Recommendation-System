@echo off
rem One-time: downloads real movie posters from Wikipedia into webapp\images\posters and updates movies.txt.
rem   poster-download.bat              download missing posters
rem   poster-download.bat --force      download all again
rem   poster-download.bat --only=12,30 only these movie ids
setlocal
cd /d "%~dp0"
call "%~dp0build.bat" || exit /b 1
java -cp build\app\WEB-INF\classes movierec.util.PosterDownloader %*
