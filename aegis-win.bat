@echo off
rem 🛡️ AEGIS Windowsクライアント 起動ランチャー (GUI)
rem 📌 同じフォルダの aegis-win.jar を javaw で起動する (要 JRE/JDK 21)
start "" javaw -Dfile.encoding=UTF-8 -jar "%~dp0aegis-win.jar" %*
