@echo off
rem 🛡️ AEGIS Windowsクライアント CLI ランチャー
rem 📌 ブリーフィングをテキスト表示する (--cli)。--diag で接続テスト
java -Dfile.encoding=UTF-8 -jar "%~dp0aegis-win.jar" --cli %*
