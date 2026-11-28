#!/usr/bin/env bash
# 🛠️ AEGIS Windowsクライアント 一括ビルド (コンパイル→テスト→jar)
# 📌 リリースビルドはこのスクリプトをプロジェクト直下で実行する。
# JAVA_HOME が未設定なら PATH 上の javac を使う。
set -euo pipefail
cd "$(dirname "$0")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"; JAR="$JAVA_HOME/bin/jar"
else
  JAVAC="$(command -v javac)"; JAVA="$(command -v java)"; JAR="$(command -v jar)"
fi
echo "javac: $JAVAC"

rm -rf build dist
mkdir -p build/classes build/test-classes dist

echo "== compile =="
find src/main/java -name '*.java' > build/sources.txt
"$JAVAC" -encoding UTF-8 -d build/classes @build/sources.txt
# 📦 jar に同梱するリソース (観測点データ) をクラス出力へコピーする
if [ -d src/main/resources ]; then cp -r src/main/resources/. build/classes/; fi

if [ "${1:-}" = "--skip-tests" ]; then
  echo "== test == (skipped)"
else
  echo "== test =="
  find src/test/java -name '*.java' > build/test-sources.txt
  "$JAVAC" -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
  "$JAVA" -Dfile.encoding=UTF-8 -cp build/classes:build/test-classes aegiswin.CoreTest
fi

echo "== package =="
printf 'Main-Class: aegiswin.Main\n' > build/manifest.mf
"$JAR" --create --file dist/aegis-win.jar --manifest build/manifest.mf -C build/classes .
cp aegis-win.bat aegis-win-cli.bat dist/ 2>/dev/null || true

echo "== version smoke =="
"$JAVA" -Dfile.encoding=UTF-8 -jar dist/aegis-win.jar --version
echo "OK: dist/aegis-win.jar"
