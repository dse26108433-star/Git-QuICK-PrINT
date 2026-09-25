@echo off
rem Prints a test page on one printer, with the label "Pickup TEST1" in the
rem bottom-right corner (add --cover to also print a cover sheet).
rem Usage:  test-print.bat "Canon iR2625"
rem         test-print.bat "Canon iR2625" C:\Users\me\Desktop\notes.pdf --copies 2
rem         test-print.bat "Canon iR-ADV C3530" photo.jpg --color
cd /d "%~dp0"
if "%~1"=="" (
  echo Give the printer name, for example:  test-print.bat "Canon iR2625"
  echo Printer names:
  java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest --list
  pause
  exit /b 1
)
java -cp print-agent.jar edu.campus.agent.print.PrinterSmokeTest %*
pause
