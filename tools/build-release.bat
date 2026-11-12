@echo off
cd /d "%~dp0\.."
set G=
if exist gradlew.bat set G=gradlew.bat
if "%G%"=="" (
  where gradle >nul 2>nul
  if errorlevel 1 ( echo Gradle not found. Install Gradle 8.13+ or open this folder in Android Studio. & exit /b 1 )
  set G=gradle
)
if not exist keystore.properties ( echo No keystore.properties - run tools\make-keystore.bat first. & exit /b 1 )
call %G% assembleRelease bundleRelease
if errorlevel 1 exit /b 1
if not exist dist mkdir dist
copy /y app\build\outputs\apk\release\*.apk dist\ >nul
copy /y app\build\outputs\bundle\release\*.aab dist\ >nul
echo.
echo Done - see the dist folder.
