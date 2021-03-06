@echo off
:: BatchGotAdmin
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"

if '%errorlevel%' NEQ '0' (
    echo This script needs to be run as Administrator as it uses the mklink command!
    pause
    goto UACPrompt
) else ( goto gotAdmin )

:UACPrompt
    echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadmin.vbs"
    set params = %*:"=""
    echo UAC.ShellExecute "cmd.exe", "/c %~s0 %params%", "", "runas", 1 >> "%temp%\getadmin.vbs"

    "%temp%\getadmin.vbs"
    del "%temp%\getadmin.vbs"
    exit /B

:gotAdmin
    pushd "%CD%"
    CD /D "%~dp0"

echo Success: Script started as Administrator.
echo This script will (mostly) automatically install the client for steam and will automatically update itself. 
echo Before continuing, please ensure that both git and OpenJDK 8 are installed and that the game isn't open.
pause
:badSteam
echo Mindustry install location (generally something like C:\Steam\steamapps\common\Mindustry):
set /p steam=
if not exist %steam%\Mindustry.exe (
    echo Error: It appears as if %steam%\Mindustry.exe doesnt exist!
    goto :badSteam
)
cd /d %steam:~0,2%
echo Success: Steam install found successfully.
echo Beginning installation of custom client (This will take a while).
git clone https://github.com/blahblahbloopster/mindustry-client.git
echo Success: Custom client installed to %steam:~0,2%\mindustry-client
echo Linking custom client with steam.
cd /D %steam%
if exist %steam%\Mindustry-vanilla.exe ( del /f Mindustry.exe ) else ( ren "Mindustry.exe" Mindustry-vanilla.exe )
mklink Mindustry.exe C:\Windows\System32\cmd.exe
echo Success: Mindustry installation patched with foo's client (to revert to vanilla, delete %steam%\Mindustry.exe and rename Mindustry-vanilla.exe to Mindustry.exe).
pause
echo Right click Mindustry in steam game list, select properties, select set launch options, copy paste the entire next line into that section and confirm.
echo.
echo /c "%steam:~0,2%\mindustry-client\launcher.bat"
echo.
pause
cd /D %steam:~0,2%\mindustry-client

echo @ECHO off>launcher.bat
echo "cd /d %steam:~0,2%\mindustry-client&&git fetch>gitStatus 2>&1&&set /p git=<gitStatus&del gitStatus&&if defined git (cd /D %steam:~0,2%\mindustry-client&&git stash&&git pull&&git stash apply&&call gradlew.bat desktop:dist --no-daemon)">>launcher.bat
echo "cd /D %steam%&&start %steam:~0,2%\mindustry-client\desktop\build\libs\Mindustry.jar">>launcher.bat

for /f "delims=" %%i in ('type "launcher.bat" ^& break ^> "launcher.bat" ') do (
    set "line=%%i"
    setlocal enabledelayedexpansion
    >>"launcher.bat" echo(!line:"=!
    endlocal
)
set search=release
set replace=steam
set textfile=build.gradle
set newfile=build2.gradle
set lineNr=0
(for /f "delims=" %%i in (%textfile%) do (
    set /a lineNr+=1
    set "line=%%i"
    setlocal enabledelayedexpansion
    if !lineNr!==37 set "line=!line:%search%=%replace%!"
    echo(!line!
    endlocal
))>"%newfile%"
del build.gradle
ren build2.gradle build.gradle
call gradlew.bat desktop:dist
echo If you have completed all these steps correctly, launching mindustry on steam should now open a cmd window which will automatically install any updates and then start the game.
echo Press any button to close this window.
pause
