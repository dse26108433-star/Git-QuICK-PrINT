@echo off
rem Runs the agent in this window instead of as a service - for testing.
rem Do NOT run this while the service is also running (stop it first:
rem   services.msc -> XeoGo Agent -> Stop).
rem Close the window or press Ctrl+C to stop.
cd /d "%~dp0"
java -jar print-agent.jar agent.yml
pause
