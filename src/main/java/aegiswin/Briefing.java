package aegiswin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

// ========================================
// 📰 Briefing (ブリーフィング集約 / AEGISサーバーモード)
// ========================================
// 📌 天気・津波・台風・EEW・地震を並列取得し、表示順に組み立てる。
// 先頭には一括把握のための「現在のまとめ」カードを置く。
public final class Briefing {
    private Briefing() {}

    // 📰 ブリーフィング (カード UI 用): すべて並列取得する
    public static List<Models.Section> briefingSections() {
        List<Models.Section> sections = new ArrayList<>();
        Models.Location[] locations = Geo.effectiveLocations();
        Future<Models.Section> tsunamiFuture = Http.FETCH_POOL.submit(() -> Quakes.tsunamiSection());
        Future<Models.EewInfo> eewFuture = Http.FETCH_POOL.submit(() -> Eew.eewInfo());
        Future<Models.Section> typhoonFuture = Http.FETCH_POOL.submit(() -> Weather.typhoonSection());
        Future<List<Models.Quake>> quakesFuture = Http.FETCH_POOL.submit(() -> Quakes.quakeItems(3));
        List<Models.Section> weatherSections = new ArrayList<>();
        {
            List<Future<String>> futures = new ArrayList<>();
            for (Models.Location loc : locations) {
                final Models.Location target = loc;
                futures.add(Http.FETCH_POOL.submit(() -> Weather.weatherText(target)));
            }
            for (int i = 0; i < locations.length; i++) {
                String body;
                // 🌐 weatherText は予報取得→現在の気象/警報が直列のため、上限は他より長く取る
                try {
                    body = futures.get(i).get(Http.DISPLAY_WAIT_SECONDS + 60, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    body = "（天気を取得できませんでした）";
                }
                weatherSections.add(new Models.Section("☀ 天気予報（" + locations[i].name() + "）", body.trim(),
                        body.contains("特別警報") ? "danger" : body.contains("発表中") ? "warn" : "info"));
            }
        }
        sections.addAll(weatherSections);
        boolean tsunamiAlert = false;
        try {
            Models.Section tsunami = tsunamiFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            tsunamiAlert = "danger".equals(tsunami.level());
            sections.add(tsunami);
        } catch (Exception e) {
            sections.add(new Models.Section("🌊 津波情報", "取得できませんでした：" + Http.friendlyError(e), "warn"));
        }
        try {
            Models.Section typhoon = typhoonFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (typhoon != null) sections.add(typhoon);
        } catch (Exception ignored) {}
        try {
            Models.EewInfo eew = eewFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (eew != null) {
                String body = eew.section().body();
                // 🌏 到達予想は生成時点の静的テキストで付ける
                // (刻むのは地震タブのカウントダウン)
                long now = System.currentTimeMillis();
                java.text.SimpleDateFormat hourMinute =
                        new java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN);
                for (Models.EewArrival arrival : eew.arrivals()) {
                    long remainSec = (arrival.arrivalMs() - now) / 1000;
                    if (remainSec > 0) {
                        body += "\n" + arrival.locName() + "の主要動到達予想 "
                                + hourMinute.format(new java.util.Date(arrival.arrivalMs()))
                                + "（あと約" + remainSec + "秒）" + (arrival.estimated() ? " ※推定" : "");
                    } else if (now - arrival.arrivalMs() <= 30_000) {
                        body += "\n" + arrival.locName() + "の主要動は到達した可能性が高いです";
                    }
                }
                sections.add(new Models.Section(eew.section().title(), body, eew.section().level()));
            }
        } catch (Exception ignored) {}
        List<Models.Quake> quakesForSummary = new ArrayList<>();
        try {
            List<Models.Quake> quakes = quakesFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            quakesForSummary = quakes;
            if (quakes.isEmpty()) {
                sections.add(new Models.Section("🌏 最新の地震", "最近の地震情報はありません。", "ok"));
            } else {
                StringBuilder sb = new StringBuilder();
                for (Models.Quake quake : quakes) {
                    sb.append(Quakes.severityEmoji(quake.sev())).append(" 震度").append(Quakes.scaleToStr(quake.maxScale()))
                            .append(" ").append(quake.place())
                            .append("\n　").append(quake.detail()).append("\n");
                }
                sections.add(new Models.Section("🌏 最新の地震（3件）", sb.toString().trim(), "info"));
            }
        } catch (Exception e) {
            sections.add(new Models.Section("🌏 最新の地震", "取得できませんでした：" + Http.friendlyError(e), "warn"));
        }
        sections.add(0, summarize(weatherSections, tsunamiAlert, quakesForSummary));
        return sections;
    }

    // 🌊 一括で把握するための先頭まとめカード (警報・津波・最新地震・今日の天気を凝縮)
    public static Models.Section summarize(List<Models.Section> weatherSections, boolean tsunamiAlert,
                                           List<Models.Quake> quakes) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> warningNames = new java.util.LinkedHashSet<>();
        for (Models.Section section : weatherSections) {
            for (String line : section.body().split("\n")) {
                if (line.startsWith("⚠ 発表中： ")) warningNames.add(line.substring("⚠ 発表中： ".length()).trim());
            }
        }
        sb.append(warningNames.isEmpty() ? "⚠ 警報・注意報: 発表なし" : "⚠ 発表中: " + String.join("、", warningNames));
        sb.append("\n🌊 津波: ").append(tsunamiAlert ? "警報・注意報あり" : "なし");
        if (!quakes.isEmpty()) {
            Models.Quake latest = quakes.get(0);
            sb.append("\n🌏 最新の地震: 震度").append(Quakes.scaleToStr(latest.maxScale())).append(" ").append(latest.place());
        }
        for (Models.Section section : weatherSections) {
            for (String line : section.body().split("\n")) {
                if (line.startsWith("今日： ")) {
                    String locName = section.title().replace("☀ 天気予報（", "").replace("）", "");
                    sb.append("\n☀ ").append(locName).append(": ").append(line.substring("今日： ".length()).trim());
                    break;
                }
            }
        }
        boolean special = false;
        for (String name : warningNames) if (name.contains("特別警報")) special = true;
        String level = (tsunamiAlert || special) ? "danger" : (!warningNames.isEmpty() ? "warn" : "ok");
        return new Models.Section("📌 現在のまとめ", sb.toString(), level);
    }

    // 🚨 地震タブの上部セクション (津波＋EEW) だけを返す。地震カード
    // 本体は呼び出し側 (Main) が quakeItems から別途組み立てる
    public static List<Models.Section> quakesSections() {
        List<Models.Section> sections = new ArrayList<>();
        try {
            sections.add(Quakes.tsunamiSection());
        } catch (Exception e) {
            sections.add(new Models.Section("🌊 津波情報", "取得できませんでした：" + Http.friendlyError(e), "warn"));
        }
        Models.Section eew = Eew.eewSection();
        if (eew != null) sections.add(eew);
        return sections;
    }

    // ----------------------------------------
    // 🖥️ AEGISサーバーモード
    // ----------------------------------------
    // 📌 同じ PC や LAN 内の AEGIS (serve) の /api/briefing を
    // Section に変換する。接続テストは /api/history を使う (Main 側)
    public static List<Models.Section> serverSections(String baseUrl) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        Map<String, Object> root = Json.obj(Http.fetchJson(base + "/api/briefing"));
        List<Object> events = Json.arr(root.get("events"));
        List<Models.Section> sections = new ArrayList<>();
        if (events.isEmpty()) {
            sections.add(new Models.Section("🛡️ AEGIS ブリーフィング", "サーバーからイベントが返りませんでした。", "warn"));
            return sections;
        }
        for (Object eventObj : events) {
            Map<String, Object> event = Json.obj(eventObj);
            String severity = Json.str(event.get("severity"), "INFO");
            String level = "CRITICAL".equals(severity) ? "danger"
                    : ("HIGH".equals(severity) || "MEDIUM".equals(severity)) ? "warn" : "info";
            StringBuilder body = new StringBuilder(Json.str(event.get("body")));
            String guide = Json.str(event.get("action_guide"));
            if (!guide.isEmpty()) body.append("\n▶ 行動ガイド： ").append(guide);
            sections.add(new Models.Section(Quakes.severityEmoji(severity) + " " + Json.str(event.get("title")),
                    body.toString(), level));
        }
        return sections;
    }
}
