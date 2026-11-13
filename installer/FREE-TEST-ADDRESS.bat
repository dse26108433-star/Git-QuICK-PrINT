@echo off
rem XeoGo: a free, temporary internet address for TESTING the server on this PC (no domain, no account).
rem It uses Cloudflare's "quick tunnel" with the cloudflared program that XeoGo Station 5.0.0 brings along.
rem Needs: XeoGo Station 5.0.0 on this PC, set up as "This PC is the XeoGo server" and running.
title XeoGo - free test address (keep this window open while testing)
setlocal
set "CF=%LOCALAPPDATA%\Programs\XeoGo Station\server\cloudflared.exe"
if not exist "%CF%" (
  echo.
  echo  XeoGo Station 5.0.0 is not installed on this PC for this Windows user.
  echo.
  pause
  exit /b 1
)
echo.
echo  ==========================================================================
echo   XeoGo - FREE TEST ADDRESS
echo  ==========================================================================
echo   In a few seconds a box appears below with an address like
echo        https://some-random-words.trycloudflare.com
echo   That is this PC's internet address FOR NOW.
echo.
echo   - Put it in config.js of your TEST website copy (apiBase), never the live one.
echo   - Keep this window open while you test. Closing it, or restarting the PC,
echo     ends the address; the next time you get a different one.
echo   - For testing only. The real shop needs your own domain
echo     (Guides\own-server.md, step 3).
echo  ==========================================================================
echo.
"%CF%" tunnel --no-autoupdate --url http://127.0.0.1:47810
echo.
echo  The test address has ended.
pause
