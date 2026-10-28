@echo off
REM Compiles the whole project into the "out" folder.
if not exist out mkdir out
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt tests\AccountingTests.java
if errorlevel 1 (echo COMPILE FAILED) else (echo Compiled OK. Now run run.bat)
