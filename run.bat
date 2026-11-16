@echo off
rem Builds the backend, sets up the frontend files, and starts the app.
rem Put this file in the same folder as Main.java, then double-click it.
cd /d "%~dp0"

if exist out rmdir /s /q out
mkdir out\static

echo Compiling backend...
javac --release 17 -d out *.java
if errorlevel 1 (
  echo.
  echo Compile failed. Check that "javac -version" works and shows 17 or newer.
  pause
  exit /b 1
)

echo Setting up frontend...
copy /y index.html out\static\ >nul
copy /y style.css out\static\ >nul
if exist app.js (
  copy /y app.js out\static\app.js >nul
) else (
  copy /y ap.js out\static\app.js >nul
)

echo.
echo Starting at http://localhost:8080  (press Ctrl+C to stop)
start "" cmd /c "timeout /t 3 >nul & start http://localhost:8080"
java -cp out com.geetansh.expensetracker.Main --demo %*
pause
