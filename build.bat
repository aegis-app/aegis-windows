@echo off
rem 🛠️ AEGIS Windowsクライアント 一括ビルド (コンパイル→テスト→jar)
rem 📌 リリースビルドはこのスクリプトをプロジェクト直下で実行する。
rem JDK 21 が必要 (JAVA_HOME か PATH に javac があること)。
setlocal
cd /d %~dp0

where javac >nul 2>nul
if errorlevel 1 (
  echo javac not found. Install JDK 21 or set JAVA_HOME.
  exit /b 1
)

if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes build\test-classes dist

echo == compile ==
dir /s /b src\main\java\*.java > build\sources.txt
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
if exist src\main\resources xcopy /e /i /y src\main\resources build\classes >nul

if "%~1"=="--skip-tests" (
  echo == test == ^(skipped^)
  goto package
)
echo == test ==
dir /s /b src\test\java\*.java > build\test-sources.txt
javac -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp build\classes;build\test-classes aegiswin.CoreTest
if errorlevel 1 exit /b 1

:package

echo == package ==
echo Main-Class: aegiswin.Main> build\manifest.mf
jar --create --file dist\aegis-win.jar --manifest build\manifest.mf -C build\classes .
if errorlevel 1 exit /b 1
copy /y aegis-win.bat dist\ >nul
copy /y aegis-win-cli.bat dist\ >nul

echo == version smoke ==
java -Dfile.encoding=UTF-8 -jar dist\aegis-win.jar --version
if errorlevel 1 exit /b 1
echo OK: dist\aegis-win.jar
endlocal
