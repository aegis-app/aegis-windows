# 🛡️ AEGIS Windowsクライアント

AEGIS（地震・津波・気象 自律アシスタント）の **Windows デスクトップクライアント** です。
Java 21 + Swing、**標準ライブラリのみ**（外部依存ゼロ）で動作します。

| 項目 | 内容 |
|---|---|
| バージョン | v1.0.0 |
| 状態 | 再実装版（旧 v1.12.1 の全機能を再現） |
| 稼働先 | 利用者の Windows PC（配布は GitHub Releases） |
| 実装 | Java 21 + Swing（標準ライブラリのみ） |
| 更新履歴 | [CHANGELOG.md](CHANGELOG.md) |

## 📖 概要

### 🔀 2つの動作モード

1. **直接取得（既定・推奨）**: 地震・津波・EEW・警報・台風は A.A.S（A2S、要設定）から、天気予報などは気象庁・Open-Meteo から取得
2. **AEGISサーバーモード**: 同じPCやLAN内の AEGIS（`serve`、Python版/Java版）の `/api/briefing` に接続

## ✨ 機能

### 🖥️ 画面

- **ブリーフィング**: 監視地点の天気カード（時間別・週間予報は表（JTable）表示）、津波情報、台風情報（発生時のみ）、最新の地震（カード形式＋重要度カラー）。取得結果は Preferences にキャッシュし、次回起動時にまずキャッシュを表示してから更新する
- **地震情報（モニター）**: 津波情報＋最新15件の地震を「震度バッジ」付きカードで表示（地点ごとの実測震度も併記）。表示中は30秒ごとに自動更新し、最新の地震を大きく表示。緊急地震速報が出ている間は到達カウントダウンを最上部に固定表示（1秒刻み）
- 緊急地震速報は **（予報）/（警報）の区別** を表示: A2S イベントの `isWarn` で判別。報番号・仮定震源要素の有無も本文に明示し、通知タイトルにも区別を出す。末尾の行動文言は推定最大震度で出し分ける（5弱以上は強い文言、震度4は中間の文言、震度3以下は注意喚起のみ。震度不明時は警報なら強い文言、予報なら注意喚起のみ）
- **A.A.S（Aegis Alert System）が地震・津波・EEW・警報・台風の唯一の情報源**（完全一本化）: 地震・津波・EEW・警報・台風を正規化して配信する中継ハブ A.A.S のみから取得し、P2P / Wolfx / 気象庁警報の直接取得は持たない（フォールバックなし）。主経路は SSE（`/v1/stream`）の常時接続で、切断時は再接続し、再接続時の再生で取りこぼしを回収する。履歴参照は REST（`/v1/events`）で回収する。接続直後の再生イベントは初期同期として取り込むだけで、新規通知にはならない。EEW の地点別予想は A.A.S では生成されないため、材料の `areaScales`（上限 `scaleTo` 込み）からクライアント側で導出する。有効化は設定式で、**設定タブで A.A.S の URL とトークンを入力したときだけ有効**（環境変数 `A2S_URL` / `A2S_TOKEN` があればそちらを優先）。未設定・到達不能時は地震系と警報・台風の情報は出ないが、天気など他の機能はそのまま動作する
- **気象警報・注意報**: 設定地点の警報表示（天気カード）と警報のトレイ通知も A.A.S 経由。警報は一次細分区域ごとの有効集合として保持し、解除は SSE の解除イベントで取り除く。地点との対応付けは A.A.S が返す区域情報（`prefName` / `areaName`）で行い、特定できない区域は県内全域へ拡大解釈しない。警報通知の出し分けは A.A.S の `severity`（特別警報=4 / 警報=3 / 注意報=2）を使う。台風の表示は A.A.S の表示行（`lines`）を使う
- **レーダー**: RainViewer の気象レーダー＋気象庁ナウキャスト併用（地理院地図に重ねて 3×3 タイル合成、地点切替可）。フレームが古い場合は注記を付ける
- **ニュース**: NHK ニュースから防災関連を優先した要約一覧（クリックでブラウザで開く）
- **設定**: 直接取得 / AEGISサーバーモードの切替、サーバーURL、サーバー接続テスト、接続テスト（自己診断）、テスト表示（デモ）、通知・トレイ常駐の設定、A.A.S連携（URL・トークン）、地点の追加（都道府県→地域区分は気象庁予報から候補を取得する選択式。取得できない場合は自由入力）
- 表示中は **5分ごとに自動更新**。設定はユーザー設定（Preferences）に保存される

### 🔔 通知

- 自動更新で新しい地震・津波・緊急地震速報・警報を検出したら、システムトレイのバルーンで通知する（直接取得モードのみ）
- 地震の通知条件は震度で選択（震度1以上〜5弱以上）。EEW・津波は常時通知。警報は「特別警報・警報のみ」か「注意報も含める」かを選択
- 通知済みイベント ID は Preferences に保存（上限200件）され、再起動後に再通知されない。起動後の初回チェックは既存イベントを登録するだけで通知しない

## 📝 使い方

1. このフォルダ一式（または zip）を任意の場所に置く
2. `aegis-win.jar` がなければ `build.bat` を実行してビルドする
3. **`aegis-win.bat` をダブルクリック**（javaw でコンソールなし起動）
   - コマンドラインからなら: `java -jar aegis-win.jar`

### 📋 必要なもの

- JDK 21（Temurin など）

### ⌨️ コマンドライン

- `java -jar aegis-win.jar --version` — バージョン表示
- `java -jar aegis-win.jar --cli` — ブリーフィングをテキストで標準出力（ヘッドレス確認用）
- `java -jar aegis-win.jar --diag` — 接続テスト（自己診断）を標準出力

## 🔨 ビルド

フルビルド（クリーンコンパイル＋単体テスト＋`aegis-win.jar` 生成）は1コマンドです。リリースビルドは利用者自身の環境でこのスクリプトを実行してください。

- Linux/macOS: `./build.sh`
- Windows: `build.bat`
- テストを省略する場合: `./build.sh --skip-tests` / `build.bat --skip-tests`

### 📦 exe 化について（任意）

Windows 上で JDK 21 の `jpackage` を使うと exe/インストーラ化できます（このプロジェクトの開発環境は Linux のため、exe は同梱していません）。

```
jpackage --type app-image --name AEGIS --input . --main-jar aegis-win.jar --main-class aegiswin.Main
```

## 🧪 テスト

- 単体テスト **384件**（`src/test/java/aegiswin/CoreTest.java`、JUnit を使わない main 方式・外部依存なし）: 震度変換、地震のグルーピング/マージ、津波、EEW（鮮度・格下げ・到達予想・地点別予想）、警報・台風の A.A.S 変換、天気（気象庁予報・Open-Meteo のモデル指定と接尾辞キー）、レーダー、ニュース、通知判定、A.A.S のイベント変換・ストア・SSE 解析をフィクスチャで検証
- CI（GitHub Actions、Ubuntu + Temurin 21）で `./build.sh`（コンパイル＋テスト＋jar＋`--version` スモーク）を実行
- ⚠ GUI の実機表示と Windows 実機での動作は未検証（開発環境に Windows がないため）。問題があればスクリーンショットで教えてください
- ⚠ 開発VM内では Java プロセスの直接通信が許可されていないため、ライブ取得は実データフィクスチャで代替検証しています（通常の Windows PC では標準の HttpClient で取得できます）

## 📁 ファイル構成

- `src/main/java/aegiswin/Models.java` — 全レコード型・定数（監視地点・都道府県・バージョン）
- `src/main/java/aegiswin/Http.java` — 取得基盤（HTTP / リトライ / 時刻変換 / 接続診断）
- `src/main/java/aegiswin/Geo.java` — 地点・地理計算（地点検証・市区町村照合・タイル座標）
- `src/main/java/aegiswin/Quakes.java` — 地震・津波（A.A.S 経由の 551 / 552 相当、震度・重要度、グルーピング/マージ）
- `src/main/java/aegiswin/Eew.java` — EEW（A.A.S 経由、到達予想・地点別予想の導出）
- `src/main/java/aegiswin/Weather.java` — 気象（気象庁予報 / Open-Meteo / A.A.S 経由の警報・台風の変換）
- `src/main/java/aegiswin/Briefing.java` — ブリーフィング集約 / AEGISサーバーモード
- `src/main/java/aegiswin/Radar.java` — 気象レーダー（RainViewer / 地理院 / 気象庁ナウキャスト）
- `src/main/java/aegiswin/News.java` — ニュース要約（NHK RSS）
- `src/main/java/aegiswin/Notify.java` — 通知（新規イベント判定・重複排除）
- `src/main/java/aegiswin/A2s.java` — A.A.S 連携（SSE クライアント・REST 履歴取得・イベント変換・重複判定。地震・津波・EEW・警報・台風の唯一の取得経路）
- `src/main/java/aegiswin/Main.java` — Swing GUI
- `src/main/java/aegiswin/UiFormat.java` — UI整形ヘルパー（色・文字列整形など、画面部品に依存しない純粋関数）
- `src/main/java/aegiswin/Json.java` / `ProxySupport.java` — 軽量JSON / 環境プロキシ対応
- `src/test/java/aegiswin/CoreTest.java` — 単体テスト（384件）
- `src/main/resources/aegiswin/intensity-stations.csv` — 震度観測点データ（同梱データ資産。生成は `tools/gen-intensity-stations.py`）

## 🔗 関連

リポジトリ: https://github.com/aegis-app/aegis-windows

設計メモと旧実装との対応表は [DESIGN.md](DESIGN.md) にあります。
