# 変更履歴

## v1.0.0（2026-11-28）

### 追加

- 統一開発フロー（Issue テンプレート / PR テンプレート / CONTRIBUTING / CI）に沿ってリポジトリをゼロから再構築
- GitHub Actions CI（Ubuntu + Temurin 21 で `./build.sh` = コンパイル＋単体テスト＋jar＋`--version` スモーク）

### 変更

- 旧 v1.12.1 の全機能を再実装し、バージョンを 1.0.0 に振り直し（設定・データ形式の互換は維持）
- 旧 `Data` ファサードを廃止し、レコード/定数を `Models` へ分離。ドメインクラス名を短縮（`FetchCore` → `Http`、`QuakeData` → `Quakes`、`EewData` → `Eew`、`WeatherData` → `Weather`、`BriefingData` → `Briefing`、`RadarData` → `Radar`、`NewsData` → `News`、`NotifyData` → `Notify`、`Locations` → `Geo`）。振る舞い・表示・出力形式は旧と同一
- 単体テストを `DataTest` から `CoreTest` へ改名し、新 API 名へ移植（384件、全件合格）
