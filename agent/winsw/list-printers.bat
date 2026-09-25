@echo off
rem Shows the exact names Windows uses for the installed printers.
rem Copy these names into the printers table (seed.sql step 2).
cd /d "%~dp0"
java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --list
pause
