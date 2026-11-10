@echo off
setlocal

rem -----------------------------------------------------------------------------
rem JOreSatTrak Launch Script for Windows 11 / Windows
rem
rem Customization Environment Variables:
rem   - JORE_JAVA_HOME : Path to JDK directory (e.g., C:\Program Files\Eclipse Adoptium\jdk-17)
rem   - JORE_JAVA_BIN  : Explicit path to java.exe binary (overrides JORE_JAVA_HOME)
rem   - JORE_JAR       : Path to JOreSatTrak JAR (defaults to target\joresattrak-0.1.jar)
rem   - JORE_JVM_OPTS  : Additional JVM options (e.g., -Xmx2g)
rem -----------------------------------------------------------------------------

rem Detect if script was launched by double-clicking in Explorer
set "IS_DOUBLE_CLICK=0"
echo %cmdcmdline% | findstr /i /c:"/c" >nul && set "IS_DOUBLE_CLICK=1"

rem Navigate to script directory
set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"

rem Strip existing surrounding quotes if any
if defined JORE_JAVA_BIN set "JORE_JAVA_BIN=%JORE_JAVA_BIN:"=%"
if defined JORE_JAVA_HOME set "JORE_JAVA_HOME=%JORE_JAVA_HOME:"=%"
if defined JAVA_HOME set "JAVA_HOME=%JAVA_HOME:"=%"
if defined JORE_JAR set "JORE_JAR=%JORE_JAR:"=%"

rem Resolve JAR file path
if defined JORE_JAR (
    set "JAR_FILE=%JORE_JAR%"
) else (
    set "JAR_FILE=%SCRIPT_DIR%target\joresattrak-0.1.jar"
)

if not exist "%JAR_FILE%" (
    echo [ERROR] Application JAR not found at "%JAR_FILE%".
    echo Please build the project first using: mvn clean package -DskipTests
    goto :handle_error
)

rem Resolve Java executable
set "JAVA_BIN="

rem 1. Explicit java binary path
if defined JORE_JAVA_BIN (
    if exist "%JORE_JAVA_BIN%" (
        set "JAVA_BIN=%JORE_JAVA_BIN%"
    ) else (
        echo [WARNING] JORE_JAVA_BIN is set to "%JORE_JAVA_BIN%", but the file was not found.
    )
)

rem 2. JORE_JAVA_HOME
if not defined JAVA_BIN if defined JORE_JAVA_HOME (
    if exist "%JORE_JAVA_HOME%\bin\java.exe" (
        set "JAVA_BIN=%JORE_JAVA_HOME%\bin\java.exe"
    ) else (
        echo [WARNING] JORE_JAVA_HOME is set to "%JORE_JAVA_HOME%", but bin\java.exe was not found.
    )
)

rem 3. JAVA_HOME
if not defined JAVA_BIN if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
    ) else (
        echo [WARNING] JAVA_HOME is set to "%JAVA_HOME%", but bin\java.exe was not found.
    )
)

rem 4. Fallback to PATH
if not defined JAVA_BIN (
    for /f "delims=" %%I in ('where java.exe 2^>nul') do (
        if not defined JAVA_BIN set "JAVA_BIN=%%I"
    )
)

if not defined JAVA_BIN (
    echo [ERROR] Java executable not found.
    echo Please install Java 17 or higher, or set JAVA_HOME, JORE_JAVA_HOME, or JORE_JAVA_BIN.
    goto :handle_error
)

rem Base JVM options:
rem   - Module opens required for AWT/Java2D and JOGL integration in Java 17+
rem   - DirectDraw and background erase disabled to prevent OpenGL/DirectDraw clashes on Windows
set DEFAULT_JVM_OPTS=--add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.java2d=ALL-UNNAMED --add-opens java.desktop/sun.awt.windows=ALL-UNNAMED -Dsun.java2d.noddraw=true -Dsun.awt.noerasebackground=true

echo [JOreSatTrak] Launching on Windows using: "%JAVA_BIN%"

"%JAVA_BIN%" %DEFAULT_JVM_OPTS% %JORE_JVM_OPTS% -jar "%JAR_FILE%" %*
set "EXIT_CODE=%ERRORLEVEL%"

if %EXIT_CODE% neq 0 (
    echo [JOreSatTrak] Process exited with error code %EXIT_CODE%.
    goto :handle_error
)

exit /b 0

:handle_error
if "%IS_DOUBLE_CLICK%"=="1" (
    echo.
    echo Press any key to exit...
    pause >nul
)
exit /b 1

