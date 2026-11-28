# 🛡️ AEGIS Windowsクライアント 設計メモ

このドキュメントは、再実装（v1.0.0）における旧実装（v1.12.1）との対応表と、判断記録をまとめたものです。日々の運用・使い方は [README.md](README.md) を参照してください。

## 📖 位置づけ

- AEGIS の Windows デスクトップクライアント。地震・津波・EEW・警報・台風は A.A.S（Aegis Alert System）からのみ取得し、天気予報などは気象庁・Open-Meteo から取得する（直接取得モード）。または AEGIS サーバー（Python版/Java版の `serve`）の `/api/briefing` を表示する（サーバーモード）
- 旧実装 v1.12.1 の **全機能を再現する** ことを絶対条件としてゼロから書き直した。再現できなかった機能はない（対応表参照）

## 🧱 技術構成

| 項目 | 内容 |
|---|---|
| 言語 / UI | Java 21 + Swing |
| 依存 | 標準ライブラリのみ（外部依存ゼロ）。ビルドは素の `javac` / `jar`（Gradle / Maven なし） |
| 設定保存 | `java.util.prefs.Preferences`（旧実装と同一キー。旧設定はそのまま引き継がれる） |
| データ資産 | `src/main/resources/aegiswin/intensity-stations.csv`（震度観測点 4,361 件。jar 同梱） |
| テスト | JUnit を使わない main 方式の単体テスト 384 件（`CoreTest`） |
| CI | GitHub Actions（Ubuntu + Temurin 21）で `./build.sh` を実行 |

> ⚠ 前提の訂正: 本再実装の依頼当初は「.NET / WinForms プロジェクト」と想定されていたが、旧実装の実物は **Java 21 + Swing** であった（.NET 版は別リポジトリ aegis-windows-cs）。そのため再実装も実物の技術構成（Java 21 + Swing、標準ライブラリのみ、素の javac）に合わせて行った。WinForms への移植は機能完全一致の観点からも行っていない。

## 🔀 旧 → 新 対応表

### ファイル / クラス

| 旧 (v1.12.1) | 新 (v1.0.0) | 備考 |
|---|---|---|
| `Data.java`（互換ファサード＋レコード/定数） | **廃止**。レコード・定数は `Models.java` へ、メソッドは各ドメインクラスへ直接配置 | 呼び出し側（Main / テスト）がドメインクラスを直接使う形に統一 |
| `FetchCore.java` | `Http.java` | `HttpStatusException` / `FetchAttempt` は `Http` のネスト型。`FETCH_POOL` / `DISPLAY_WAIT_SECONDS`、時刻変換（`parseP2pTimeMs` / `parseJmaTimeMs`）、接続診断（`diagnose` / `formatDiag`）もここ |
| `Locations.java` | `Geo.java` | 地点検証・市区町村照合に加え、タイル座標・ハバーサイン距離も集約 |
| `QuakeData.java` | `Quakes.java` | `parseEarthquakes` / `parseTsunami` 等の入口名は旧と同一に維持 |
| `EewData.java` | `Eew.java` | `parseEew` / `parseArrivals` / `sectionFromItem` 等を維持。定数 `EEW_DANGER_MS` → `DANGER_MS`、`EEW_DISPLAY_MS` → `DISPLAY_MS` |
| `WeatherData.java` | `Weather.java` | 気象庁予報 / Open-Meteo に加え、A.A.S 警報・台風の変換（`warningEntriesFromA2s` / `warningNamesFromA2s` / `typhoonFromA2s` / `typhoonSection`）もここ |
| `BriefingData.java` | `Briefing.java` | ブリーフィング集約・地震タブ集約・サーバーモード |
| `RadarData.java` | `Radar.java` | `latestRadarPath` → `latestPath`、`parseJmaTargetTimes` → `parseTargetTimes`、`radarCoverTiles` → `coverTiles` など短縮。定数 `RADAR_STALE_MINUTES` → `STALE_MINUTES` |
| `NewsData.java` | `News.java` | `summarizeNews` → `summarize` |
| `NotifyData.java` | `Notify.java` | `parseQuakeNotifyEvents` → `parseQuakeEvents`、`warningNotifyEvent` → `warningEvent`、`warningNotifyId` → `warningId`、`isFreshForNotify` → `isFresh`、`filterNewEvents` → `filterNew` |
| `A2s.java` | `A2s.java`（同名） | 内部クラス `A2sStreamClient` → `StreamClient` |
| `UiFormat.java` / `Json.java` / `ProxySupport.java` | 同名 | 役割は同一 |
| `Main.java` | `Main.java`（同名） | Swing GUI。ブリーフィングキャッシュ上限判定は `UiFormat.briefingCacheFits` を直接使う |
| `DataTest.java`（384件） | `CoreTest.java`（384件） | 全チェックを新 API 名へ移植し、全件合格を確認。バージョン期待値のみ 1.12.1 → 1.0.0 に変更 |

### 機能

| 機能 | 旧 | 新 | 備考 |
|---|---|---|---|
| 直接取得 / AEGISサーバーモード | ✓ | ✓ | Preferences キー・サーバー API とも同一 |
| ブリーフィング（要約・天気カード・津波・台風・最新地震） | ✓ | ✓ | 時間別/週間予報の表表示も同一 |
| ブリーフィングキャッシュ（次回起動時に先行表示） | ✓ | ✓ | 上限 100KB（UTF-8）、失敗セクションは保存しない規則も同一 |
| 地震モニター（30秒更新・最新を大きく表示） | ✓ | ✓ | |
| EEW 到達カウントダウン（1秒刻み・最上部固定） | ✓ | ✓ | |
| EEW（予報）/（警報）区別・行動文言の出し分け | ✓ | ✓ | |
| A.A.S SSE 主経路＋REST 回収、再生は通知しない | ✓ | ✓ | 重複判定は (key, updatedAt)、EEW 同一視は baseKey |
| 警報の有効集合（解除で除去）・区域照合 | ✓ | ✓ | prefecture 全域への拡大解釈なし |
| 天気（気象庁予報＋Open-Meteo、モデル指定 `jma_seamless,ecmwf_ifs025`） | ✓ | ✓ | 降水確率は ECMWF 側キー。best_match への暗黙フォールバックなし |
| レーダー（RainViewer＋気象庁ナウキャスト、3×3 合成・マーカー・鮮度注記） | ✓ | ✓ | |
| ニュース（NHK RSS、防災優先、クリックで開く） | ✓ | ✓ | |
| 通知（トレイバルーン、震度条件、注意報 opt-in、既読保存・初回 priming） | ✓ | ✓ | |
| トレイ格納（閉じる/最小化） | ✓ | ✓ | |
| テスト表示（デモ。サンプル EEW カウントダウン連動） | ✓ | ✓ | 実データ更新で自動解除 |
| 接続テスト（自己診断）・サーバー接続テスト | ✓ | ✓ | GUI ダイアログと `--diag` の両方 |
| 任意地点の追加（都道府県→地域区分の候補取得、座標検証） | ✓ | ✓ | |
| CLI（`--version` / `--cli` / `--diag`） | ✓ | ✓ | |
| ランチャー（`aegis-win.bat` / `aegis-win-cli.bat`） | ✓ | ✓ | |
| 震度観測点 CSV 同梱＋生成スクリプト | ✓ | ✓ | 旧と同一データ（コードからは参照されないデータ資産として保持） |

**未再現の機能: なし。**

## 📝 判断記録

1. **技術構成は実物に合わせる（Java 21 + Swing）**: 依頼時の想定（.NET / WinForms）は旧実装の実物と異なっていた。機能完全一致を優先し、実物の構成で再実装した
2. **`Data` ファサードを廃止**: 旧は「互換のためだけの委譲層」が残っていた。再実装ではレコード/定数を `Models` に分離し、呼び出し側がドメインクラスを直接使う。振る舞い・出力形式・設定キーは変えていない
3. **A.A.S 一本化を維持**: 地震・津波・EEW・警報・台風の取得経路は A.A.S のみ。P2P / Wolfx / 気象庁警報の直接取得とフォールバックは旧（v1.11.0 / v1.12.0 の決定）と同じく持たない
4. **Open-Meteo のモデル指定を維持**: `models=jma_seamless,ecmwf_ifs025` の順序指定と接尾辞キー読みを厳密に再現。暗黙の best_match フォールバックはしない
5. **地点震度は実測のみ**: 市区町村一致の DetailScale 観測点がある場合だけ数値を出し、それ以外は「実測なし」。最寄り観測点からの推定表示は旧で廃止済みのため再実装でも持たない
6. **深さのセンチネル処理を維持**: 深さ 0 = 「ごく浅い」、負値・欠落・null = 「不明」（0km と表示しない）。後報で判明した深さが主報のセンチネルに勝つ
7. **津波の有無は明示値のみで判定**: 明示的な None だけを「心配なし」とし、Unknown / Checking / 未知の値は「確認中」とする
8. **時刻依存テストは時刻を固定する**: テストは `nowMs` を引数で渡せる設計を維持し、実時刻に依存させない（他リポジトリで実時刻依存テストが時間帯で落ちた教訓）
9. **CI は Ubuntu で回す**: ビルドとテストはプラットフォーム非依存（素の javac＋main 方式テスト）のため、Windows ランナーは使わず Ubuntu + Temurin 21 で `./build.sh` を実行する。GUI の起動確認は CI でも行わない（ヘッドレスのため）。Windows 実機確認は利用者のリリースビルド時に委ねる
10. **バージョンは 1.0.0 に振り直し**: 再実装に伴いリセット。設定（Preferences）・データ形式の互換は維持しているため、旧版からの移行で設定は引き継がれる
11. **A.A.S トークンをコードに含めない**: トークンは利用者が設定タブ（または環境変数）で入力するものだけを使い、リポジトリ・コードには一切含めない

## ⚠ 未検証事項

- GUI の実機表示・Windows 実機での動作（開発環境が Linux のため。コンパイル＋単体テスト384件＋jar の `--version` スモークまでを確認済み）
- ライブネットワーク取得（開発VMでは Java プロセスの直接通信が制限されるため、旧版と同じく実データフィクスチャで代替検証）
- `build.bat` の Windows 実機実行（スクリプト内容は旧版と同等構成）
