@echo off
rem Saves your TMDB API key into movie-api.properties. The key is never shown, logged or sent anywhere except TMDB.
rem
rem   setup-key.bat              asks for the key (hidden typing) - run it in a normal window / double-click
rem   setup-key.bat --clipboard  copy the key from the TMDB website first (Ctrl+C), then run this: no typing needed
setlocal
cd /d "%~dp0"
if /i "%~1"=="--clipboard" goto clip

powershell -NoProfile -Command "$s = Read-Host 'Paste your TMDB API key (typing is hidden), then press Enter' -AsSecureString; $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s); $k = [Runtime.InteropServices.Marshal]::PtrToStringAuto($b); if (-not $k -or -not $k.Trim()) { Write-Host 'No key entered - nothing saved.'; exit 1 }; [IO.File]::WriteAllText('movie-api.properties', 'MOVIE_API_KEY=' + $k.Trim() + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false))); Write-Host ('Saved movie-api.properties (' + $k.Trim().Length + ' characters). Now run live-check.bat, then run.bat.')"
goto end

:clip
powershell -NoProfile -Command "$k = ((Get-Clipboard -Raw) + '').Trim().Trim([char]34); if ($k -notmatch '^[0-9a-fA-F]{32}$' -and $k -notmatch '^eyJ[\w-]+\.[\w-]+\.[\w-]+$') { Write-Host 'The clipboard does not look like a TMDB key (expected 32 letters/digits, or a long token starting with eyJ). Copy the key again and retry. Nothing saved.'; exit 1 }; [IO.File]::WriteAllText('movie-api.properties', 'MOVIE_API_KEY=' + $k + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false))); Write-Host ('Saved movie-api.properties from the clipboard (' + $k.Length + ' characters).')"

:end
if "%~1"=="" pause
