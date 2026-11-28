package aegiswin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ========================================
// 🧪 CoreTest (データ層の単体テスト)
// ========================================
// 📌 JUnit を使わない main 方式の自己完結テスト。外部ネットワークは
// 使わず、気象庁電文・A.A.S イベントのフィクスチャを JSON 文字列で
// 直接組み立てて検証する (実行方法は build.sh)。
public final class CoreTest {
    private static int passed = 0;

    private static void check(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAILED: " + name);
        passed++;
        System.out.println("ok " + name);
    }

    public static void main(String[] args) {
        // scale
        check(Quakes.scaleToStr(45).equals("5弱"), "scale 45");
        check(Quakes.scaleToStr(70).equals("7"), "scale 70");
        check(Quakes.severityOf(40, 4.0).equals("HIGH"), "severity high");

        // earthquakes with local intensity
        Object quakes = Json.parse("[{\"id\":\"q1\",\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":40}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":40},{\"pref\":\"埼玉県\",\"addr\":\"さいたま市\",\"scale\":30}]}]");
        List<Models.Quake> qs = Quakes.parseEarthquakes(quakes);
        check(qs.size() == 1, "quake count");
        check(qs.get(0).place().equals("群馬県南部"), "quake place");
        check(qs.get(0).detail().contains("11/17 09:58"), "quake short time");
        // B-2: 地点震度ではなく「県内の最大震度」であることを明記する
        check(qs.get(0).detail().contains("群馬県内の最大震度 4"), "quake local gunma pref max");
        check(qs.get(0).detail().contains("埼玉県内の最大震度 3"), "quake local saitama pref max");
        check(!qs.get(0).detail().contains("地元震度"), "quake no local-intensity wording");
        check(qs.get(0).sev().equals("HIGH"), "quake sev");

        // tsunami: none / warning / cancelled
        check(Quakes.parseTsunami(Json.parse("[]")).level().equals("ok"), "tsunami none");
        Object tw = Json.parse("[{\"cancelled\":false,\"time\":\"2026/11/17 10:00:00\",\"areas\":[{\"name\":\"宮城県沿岸\",\"grade\":\"Warning\"}]}]");
        check(Quakes.parseTsunami(tw).level().equals("danger"), "tsunami warning level");
        check(Quakes.parseTsunami(tw).body().contains("津波警報"), "tsunami warning body");
        Object tc = Json.parse("[{\"cancelled\":true,\"time\":\"2026/11/17 11:00:00\",\"areas\":[{\"name\":\"宮城県沿岸\",\"grade\":\"Watch\"}]}]");
        check(Quakes.parseTsunami(tc).level().equals("ok"), "tsunami cancelled");

        // forecast parse (area match + spacing cleanup)
        Object fc = Json.parse("[{\"timeSeries\":[{\"areas\":[{\"area\":{\"name\":\"南部\"},\"weathers\":[\"くもり　夕方　から　雨\",\"晴れ　時々　くもり\"]},{\"area\":{\"name\":\"北部\"},\"weathers\":[\"雪\"]}]}]}]");
        String[] w = Weather.parseForecast(fc, "南部");
        check(w.length == 2 && w[0].equals("くもり 夕方 から 雨"), "forecast cleanup");
        check(w[1].startsWith("晴れ"), "forecast tomorrow");

        // warnings (A2S v0.5.0 形式: 一次細分区域ごとに1件、名称・重要度つき)
        Map<String, Object> wnEv = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T12:00:00+09:00\",\"headline\":\"群馬県では、雷注意報が発表されています。\",\"warnings\":[{\"code\":\"03\",\"name\":\"大雨警報\",\"status\":\"継続\",\"severity\":3},{\"code\":\"14\",\"name\":\"雷注意報\",\"status\":\"発表\",\"severity\":2},{\"code\":\"99\",\"name\":\"その他の警報・注意報（コード99）\",\"status\":\"発表\",\"severity\":2}],\"maxSeverity\":3,\"time\":\"2026-11-17T12:00:00+09:00\",\"released\":false}"));
        check(A2s.isA2sEvent(wnEv), "a2s warning event valid");
        check(!A2s.isA2sEvent(Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"x\"}"))), "a2s incomplete warning rejected");
        check(Weather.warningNamesFromA2s(wnEv).toString().contains("雷注意報"), "a2s warning advisory included");
        check(Weather.warningNamesFromA2s(wnEv).toString().contains("大雨警報"), "a2s warning alert included");
        check(Weather.warningNamesFromA2s(wnEv).toString().contains("コード99"), "a2s warning unknown code kept");
        check(Weather.warningEntriesFromA2s(wnEv).get(0).severity() == 3, "a2s warning severity carried");
        // 解除イベント (released: true) は発表中として扱わない
        Map<String, Object> wnRel = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T13:00:00+09:00\",\"headline\":\"\",\"warnings\":[],\"maxSeverity\":0,\"time\":\"2026-11-17T13:00:00+09:00\",\"released\":true}"));
        check(A2s.isA2sEvent(wnRel), "a2s warning released event valid");
        check(Weather.warningNamesFromA2s(wnRel).isEmpty(), "a2s warning released has no names");
        // 地点照合は A2S の区域情報 (prefName / areaName) で行う: 高崎 (群馬県・南部) には南部が当たり、北部は当たらない
        Map<String, Object> wnNorth = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100020\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100020\",\"areaName\":\"群馬県北部\",\"reportDatetime\":\"2026-11-17T12:00:00+09:00\",\"headline\":\"\",\"warnings\":[{\"code\":\"06\",\"name\":\"大雪警報\",\"status\":\"発表\",\"severity\":3}],\"maxSeverity\":3,\"time\":\"2026-11-17T12:00:00+09:00\",\"released\":false}"));
        check(A2s.matchWarningEvent(List.of(wnEv, wnNorth), Models.TAKASAKI) == wnEv, "a2s warning matched to takasaki area");
        check(A2s.matchWarningEvent(List.of(wnNorth), Models.TAKASAKI) == null, "a2s warning other area not matched");
        check(A2s.matchWarningEvent(List.of(wnEv), Models.OMIYA) == null, "a2s warning other pref not matched");
        // ストア: 警報は有効集合として保持し、解除イベントで取り除かれる
        A2s.EventStore wStore = new A2s.EventStore();
        Map<String, Object> wnEvUpd = new java.util.HashMap<>(wnEv);
        wnEvUpd.put("updatedAt", "2026-11-17T03:00:00.000Z");
        check(wStore.accept(wnEvUpd), "a2s warning store accepts");
        check(!wStore.accept(wnEvUpd), "a2s warning store dedups same updatedAt");
        check(wStore.warningEvents().size() == 1, "a2s warning store holds active area");
        Map<String, Object> wnRelUpd = new java.util.HashMap<>(wnRel);
        wnRelUpd.put("updatedAt", "2026-11-17T04:00:00.000Z");
        check(wStore.accept(wnRelUpd), "a2s warning release removes from store");
        check(wStore.warningEvents().isEmpty(), "a2s warning store empty after release");
        check(!wStore.accept(wnRelUpd), "a2s warning release for unknown area is no change");

        // tsunami: grade は全エリア中の最大で判定する (先頭が Watch でも MajorWarning があれば danger)
        Object tm = Json.parse("[{\"cancelled\":false,\"time\":\"2026/11/17 10:00:00\",\"areas\":[{\"name\":\"A沿岸\",\"grade\":\"Watch\"},{\"name\":\"B沿岸\",\"grade\":\"MajorWarning\",\"maxHeight\":{\"description\":\"3m\"}}]}]");
        check(Quakes.parseTsunami(tm).level().equals("danger"), "tsunami max grade wins");
        check(Quakes.parseTsunami(tm).body().contains("大津波警報"), "tsunami max grade body");
        check(Quakes.parseTsunami(tm).body().contains("予想 3m"), "tsunami height shown");

        // sentinel -1 (震度不明・M不明・深さ不明: -1 は震源未確定のセンチネル。
        // 「ごく浅い」は depth=0 が表す — 下の深さ回帰テスト参照)
        Object qn = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":-1,\"domesticTsunami\":\"Unknown\",\"hypocenter\":{\"name\":\"不明\",\"magnitude\":-1,\"depth\":-1}},\"points\":[]}]");
        List<Models.Quake> qsn = Quakes.parseEarthquakes(qn);
        check(qsn.get(0).detail().contains("M不明"), "sentinel mag");
        check(qsn.get(0).detail().contains("深さ不明"), "sentinel depth");
        check(Quakes.scaleToStr(-1).equals("不明"), "sentinel scale");

        // forecast area code + pops + temps
        Object fc2 = Json.parse("[{\"timeSeries\":["
                + "{\"areas\":[{\"area\":{\"name\":\"南部\",\"code\":\"100010\"},\"weathers\":[\"晴れ\"]},{\"area\":{\"name\":\"北部\",\"code\":\"100020\"},\"weathers\":[\"雪\"]}]},"
                + "{\"timeDefines\":[\"2026-11-17T12:00:00+09:00\",\"2026-11-17T18:00:00+09:00\",\"2026-11-18T00:00:00+09:00\"],\"areas\":[{\"area\":{\"name\":\"南部\",\"code\":\"100010\"},\"pops\":[\"60\",\"90\",\"70\"]}]},"
                + "{\"areas\":[{\"area\":{\"name\":\"前橋\",\"code\":\"42251\"},\"temps\":[\"13\",\"21\",\"9\",\"20\"]}]}"
                + "]}]");
        check(Weather.forecastAreaCode(fc2, "南部").equals("100010"), "forecast area code");
        // A-6: parsePops は現在時刻以降の枠だけを表示する (時刻を明示する overload で検証)
        long popsNow = java.time.ZonedDateTime.of(2026, 11, 17, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        check(Weather.parsePops(fc2, "100010", popsNow).contains("12〜18時 60%"), "pops formatted");
        check(Weather.parsePops(fc2, "100010", popsNow).contains("11/18 0〜6時 70%"), "pops next day");
        long popsLater = java.time.ZonedDateTime.of(2026, 11, 17, 19, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        String popsFiltered = Weather.parsePops(fc2, "100010", popsLater);
        check(!popsFiltered.contains("12〜18時"), "pops elapsed slot dropped");
        check(popsFiltered.contains("18〜0時 90%"), "pops current slot kept");
        long popsAllGone = java.time.ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        check(Weather.parsePops(fc2, "100010", popsAllGone).isEmpty(), "pops all elapsed empty");
        check(Weather.parseTemps(fc2).contains("今日 13〜21℃"), "temps formatted");

        // A-1: 気温も一次細分区域コードで照合する
        // (2) temps のコードが区域コードと一致しない形式では、天気系列の並び順に対応する代表地点を選ぶ
        Object fcTempsOrder = Json.parse("[{\"timeSeries\":["
                + "{\"areas\":[{\"area\":{\"name\":\"南部\",\"code\":\"100010\"},\"weathers\":[\"晴れ\"]},{\"area\":{\"name\":\"北部\",\"code\":\"100020\"},\"weathers\":[\"雪\"]}]},"
                + "{\"areas\":[{\"area\":{\"name\":\"地点A\",\"code\":\"900001\"},\"temps\":[\"1\",\"2\",\"3\",\"4\"]},{\"area\":{\"name\":\"地点B\",\"code\":\"900002\"},\"temps\":[\"10\",\"20\",\"15\",\"25\"]}]}"
                + "]}]");
        check(Weather.parseTemps(fcTempsOrder, "100020").contains("気温（地点B）： 今日 10〜20℃ / 明日 15〜25℃"), "temps matched by weather area order");
        check(Weather.parseTemps(fcTempsOrder, "100010").contains("気温（地点A）"), "temps first area by order");
        check(Weather.parseTemps(fcTempsOrder, "999999").contains("気温（地点A）"), "temps unknown code falls back to first");
        check(Weather.parseTemps(fcTempsOrder).contains("気温（地点A）"), "temps no code falls back to first");
        // (1) temps のコードが区域コードと完全一致する形式では、並び順よりコード一致を優先する
        Object fcTempsCode = Json.parse("[{\"timeSeries\":["
                + "{\"areas\":[{\"area\":{\"name\":\"南部\",\"code\":\"100010\"},\"weathers\":[\"晴れ\"]},{\"area\":{\"name\":\"北部\",\"code\":\"100020\"},\"weathers\":[\"雪\"]}]},"
                + "{\"areas\":[{\"area\":{\"name\":\"北部地点\",\"code\":\"100020\"},\"temps\":[\"10\",\"20\",\"15\",\"25\"]},{\"area\":{\"name\":\"南部地点\",\"code\":\"100010\"},\"temps\":[\"1\",\"2\",\"3\",\"4\"]}]}"
                + "]}]");
        check(Weather.parseTemps(fcTempsCode, "100010").contains("気温（南部地点）： 今日 1〜2℃"), "temps matched by code over order");

        // EEW: メタデータのみのレコードは無視し、古い EEW は出さない
        Object eewOld = Json.parse("[{\"code\":554,\"id\":\"x\",\"time\":\"2020/01/01 00:00:00.000\",\"type\":\"Full\"}]");
        check(Eew.parseEew(eewOld) == null, "eew metadata-only ignored");

        // friendly errors
        check(Http.friendlyError(new IllegalStateException("HTTP 503")).contains("HTTP 503"), "friendly http");
        check(Http.friendlyError(new java.net.http.HttpTimeoutException("timeout")).contains("タイムアウト"), "friendly timeout");
        check(!Http.friendlyError(new Exception("Read timed out weird")).contains("Read"), "friendly hides raw");

        // radar: 最新フレーム選択 + タイル座標 (東京駅 z8 = 227,100)
        Object rv = Json.parse("{\"radar\":{\"past\":[{\"time\":100,\"path\":\"/v2/radar/old\"},{\"time\":200,\"path\":\"/v2/radar/new\"}]}}");
        check(Radar.latestPath(rv).equals("/v2/radar/new"), "radar latest frame");
        check(Radar.latestPath(Json.parse("{}")).isEmpty(), "radar no frame");

        // C-3: フレームの相対時刻表示 (約N分前の観測 / 閾値超過時は古い旨の注記)
        long frameNow = 1_700_000_000_000L;
        check(Radar.frameAgeMinutes(1_700_000_000L - 300, frameNow) == 5, "radar age minutes");
        check(Radar.frameAgeNote(1_700_000_000L - 300, frameNow).equals("約5分前の観測"), "radar age note fresh");
        check(Radar.frameAgeNote(1_700_000_000L - 20 * 60, frameNow).equals("約20分前の観測"), "radar age note at threshold no stale");
        check(Radar.frameAgeNote(1_700_000_000L - 21 * 60, frameNow).contains("約21分前の観測"), "radar age note stale minutes");
        check(Radar.frameAgeNote(1_700_000_000L - 21 * 60, frameNow).contains("最新の状況と異なる場合があります"), "radar age note stale warning");
        check(Radar.frameAgeNote(0, frameNow).isEmpty(), "radar age note unknown empty");
        check(Radar.frameAgeMinutes(1_700_000_000L + 600, frameNow) == 0, "radar age future clamped");

        // C-2: マーカーはタイル中心固定ではなく小数タイル座標から算出した実位置
        int[] mpTokyo = Geo.markerPixel(35.6812, 139.7671, 8, 226, 99);
        check(mpTokyo[0] == 356 && mpTokyo[1] == 463, "marker pixel tokyo actual position");
        check(mpTokyo[0] != 384 || mpTokyo[1] != 384, "marker pixel not tile center");
        int[] mpTakasaki = Geo.markerPixel(36.3219, 139.0023, 8, 225, 99);
        check(mpTakasaki[0] == 473 && mpTakasaki[1] == 318, "marker pixel takasaki actual position");
        // キャンバス左端 (原点タイルの西端) の経度では px = 0
        check(Geo.markerPixel(35.6812, 136.40625, 8, 225, 99)[0] == 0, "marker pixel west edge");
        // 東へ移動すれば px は増える
        check(Geo.markerPixel(35.6812, 139.0, 8, 226, 99)[0] < Geo.markerPixel(35.6812, 140.0, 8, 226, 99)[0], "marker pixel monotonic lon");

        // B-9: P2P 時刻パースはデフォルト TZ が JST 以外でも Asia/Tokyo として解釈する
        java.util.TimeZone origTz = java.util.TimeZone.getDefault();
        long p2pUtcParsed;
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            p2pUtcParsed = Http.parseP2pTimeMs("2026/11/17 12:00:00");
        } finally {
            java.util.TimeZone.setDefault(origTz);
        }
        long p2pExpected = java.time.ZonedDateTime.of(2026, 11, 17, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        check(p2pUtcParsed == p2pExpected, "p2p time parsed as Asia/Tokyo regardless of default tz");
        int[] xy = Geo.tileXY(35.6812, 139.7671, 8);
        check(xy[0] == 227 && xy[1] == 100, "tile xy tokyo z8");
        check(Radar.tileUrl("/v2/radar/x", 8, 227, 100).contains("/256/8/227/100/2/1_1.png"), "radar tile url");
        check(Radar.gsiTileUrl(8, 227, 100).endsWith("/8/227/100.png"), "gsi tile url");
        check(Radar.tileUrl("/v2/radar/x", 7, 113, 50).contains("/256/7/113/50/2/1_1.png"), "radar tile url z7");

        // レーダー z7 カバー範囲 (雨タイルは z7 を 512×512 に拡大して描く)
        // 中心タイル偶数: 高崎 z8 = (226,100) → 原点 (225,99)、オフセットは -256/256
        int[] tk = Geo.tileXY(36.3219, 139.0023, 8);
        check(tk[0] == 226 && tk[1] == 100, "tile xy takasaki z8");
        List<int[]> covE = Radar.coverTiles(tk[0] - 1, tk[1] - 1);
        check(covE.size() == 4, "radar cover even count");
        check(covE.get(0)[0] == 112 && covE.get(0)[1] == 49 && covE.get(0)[2] == -256 && covE.get(0)[3] == -256, "radar cover even tile0");
        check(covE.get(1)[0] == 113 && covE.get(1)[1] == 49 && covE.get(1)[2] == 256 && covE.get(1)[3] == -256, "radar cover even tile1");
        check(covE.get(3)[0] == 113 && covE.get(3)[1] == 50 && covE.get(3)[2] == 256 && covE.get(3)[3] == 256, "radar cover even tile3");
        // 中心タイル奇数: 東京 z8 = (227,100) → 原点 (226,99)、x のオフセットは 0/512
        List<int[]> covO = Radar.coverTiles(226, 99);
        check(covO.size() == 4, "radar cover odd count");
        check(covO.get(0)[0] == 113 && covO.get(0)[1] == 49 && covO.get(0)[2] == 0 && covO.get(0)[3] == -256, "radar cover odd tile0");
        check(covO.get(3)[0] == 114 && covO.get(3)[1] == 50 && covO.get(3)[2] == 512 && covO.get(3)[3] == 256, "radar cover odd tile3");
        // 原点が両軸とも偶数: 中心 (227,101) → 原点 (226,100)、オフセットは 0/512 のみ
        List<int[]> covEE = Radar.coverTiles(226, 100);
        check(covEE.get(0)[0] == 113 && covEE.get(0)[1] == 50 && covEE.get(0)[2] == 0 && covEE.get(0)[3] == 0, "radar cover even-origin tile0");
        check(covEE.get(3)[0] == 114 && covEE.get(3)[1] == 51 && covEE.get(3)[2] == 512 && covEE.get(3)[3] == 512, "radar cover even-origin tile3");

        // RSS parse + 要約 + 防災キーワード
        String rss = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss version=\"2.0\"><channel>"
                + "<item><title>震度5弱の地震が発生</title><link>https://example.com/n1</link>"
                + "<description>&lt;p&gt;テストの説明文です。&lt;/p&gt;</description><pubDate>Tue, 17 Nov 2026 06:30:00 +0900</pubDate></item>"
                + "<item><title>プロ野球の結果</title><link>https://example.com/n2</link>"
                + "<description>試合の結果です。</description><pubDate>Tue, 17 Nov 2026 07:00:00 +0900</pubDate></item>"
                + "</channel></rss>";
        List<Models.NewsItem> news = News.parseRss(rss);
        check(news.size() == 2, "rss count");
        check(news.get(0).title().contains("震度5弱"), "rss title");
        check(news.get(0).time().equals("06:30"), "rss time jst");
        check(news.get(0).summary().equals("テストの説明文です。"), "rss summary strips html");
        check(News.isDisasterNews(news.get(0).title()), "disaster keyword hit");
        check(!News.isDisasterNews(news.get(1).title()), "disaster keyword miss");
        String longDesc = "<p>" + "あ".repeat(100) + "</p>";
        check(News.summarize(longDesc).length() == 81 && News.summarize(longDesc).endsWith("…"), "summary truncated");
        check(News.summarize("短い文").equals("短い文"), "summary short kept");

        // EEW 到達予想: arrivalTime 優先 / 無ければ S波推定
        long originMs = Http.parseP2pTimeMs("2026/11/17 12:00:00");
        check(Geo.haversineKm(36.3219, 139.0023, 36.3219, 139.0023) == 0, "haversine zero");
        check(Eew.estimateArrivalMs(originMs, 36.3219, 139.0023, 35, Models.TAKASAKI) == originMs + 10000, "arrival estimate depth only");
        Object eewArr = Json.parse("[{\"code\":554,\"time\":\"2026/11/17 12:00:05\",\"earthquake\":{\"time\":\"2026/11/17 12:00:00\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\",\"latitude\":36.3219,\"longitude\":139.0023,\"depth\":35}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45,\"arrivalTime\":\"2026/11/17 12:01:00\"}]}]");
        List<Models.EewArrival> arrs = Eew.parseArrivals(eewArr, Models.LOCATIONS, originMs + 5000);
        check(arrs.size() == 1 && arrs.get(0).locName().equals("高崎"), "arrival matched pref");
        check(!arrs.get(0).estimated() && arrs.get(0).arrivalMs() == Http.parseP2pTimeMs("2026/11/17 12:01:00"), "arrival uses arrivalTime");
        Object eewEst = Json.parse("[{\"code\":554,\"time\":\"2026/11/17 12:00:05\",\"earthquake\":{\"time\":\"2026/11/17 12:00:00\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\",\"latitude\":36.3219,\"longitude\":139.0023,\"depth\":35}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
        List<Models.EewArrival> arrs2 = Eew.parseArrivals(eewEst, Models.LOCATIONS, originMs + 5000);
        check(arrs2.size() == 1 && arrs2.get(0).estimated() && arrs2.get(0).arrivalMs() == originMs + 10000, "arrival estimated fallback");

        // EEW 区域の採点: 先頭の区域 (名前不一致・到達時刻なし) より、到達時刻を持つ区域を優先する
        Object eewScoreA = Json.parse("[{\"code\":554,\"time\":\"2026/11/17 12:00:05\",\"earthquake\":{\"time\":\"2026/11/17 12:00:00\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\",\"latitude\":36.3219,\"longitude\":139.0023,\"depth\":35}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県北部\",\"scale\":45},{\"pref\":\"群馬県\",\"name\":\"群馬県東部\",\"scale\":45,\"arrivalTime\":\"2026/11/17 12:01:00\"}]}]");
        List<Models.EewArrival> arrsA = Eew.parseArrivals(eewScoreA, Models.LOCATIONS, originMs + 5000);
        check(arrsA.size() == 1 && !arrsA.get(0).estimated() && arrsA.get(0).arrivalMs() == Http.parseP2pTimeMs("2026/11/17 12:01:00"), "arrival prefers area with arrivalTime");
        // EEW 区域の採点: 名前一致でも到達時刻を持つ区域 (同名の2件目) を優先する
        Object eewScoreB = Json.parse("[{\"code\":554,\"time\":\"2026/11/17 12:00:05\",\"earthquake\":{\"time\":\"2026/11/17 12:00:00\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\",\"latitude\":36.3219,\"longitude\":139.0023,\"depth\":35}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45},{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45,\"arrivalTime\":\"2026/11/17 12:02:00\"}]}]");
        List<Models.EewArrival> arrsB = Eew.parseArrivals(eewScoreB, Models.LOCATIONS, originMs + 5000);
        check(arrsB.size() == 1 && !arrsB.get(0).estimated() && arrsB.get(0).arrivalMs() == Http.parseP2pTimeMs("2026/11/17 12:02:00"), "arrival prefers name match with arrivalTime");

        // 週間予報の整形
        Object om = Json.parse("{\"daily\":{\"time\":[\"2026-11-17\",\"2026-11-18\",\"2026-11-19\"],\"weather_code\":[0,61,3],\"temperature_2m_max\":[21.2,15.4,12.6],\"temperature_2m_min\":[9.1,8.2,5.5]}}");
        check(Weather.formatWeekly(om).startsWith("週間："), "weekly prefix");
        check(Weather.formatWeekly(om).contains("11/17 ☀ 9〜21℃"), "weekly day format");
        check(Weather.formatWeekly(om).contains("11/18 🌧"), "weekly rain emoji");

        // 時間別予報の整形 (現在 2026-11-17 13:20 JST → 15時から・3時間刻み)
        long nowMs = java.time.ZonedDateTime.of(2026, 11, 17, 13, 20, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        Object omh = Json.parse("{\"hourly\":{\"time\":[\"2026-11-17T12:00\",\"2026-11-17T15:00\",\"2026-11-17T18:00\",\"2026-11-18T00:00\"],\"temperature_2m\":[10.0,12.4,11.0,8.0],\"precipitation_probability\":[10,20,60,30],\"weather_code\":[0,0,61,3]}}");
        String hourly = Weather.formatHourly(omh, nowMs);
        check(hourly.contains("15時 ☀ 12℃ 降水20%"), "hourly entry format");
        check(!hourly.contains("12時"), "hourly skips past");
        check(hourly.contains("11/18 0時"), "hourly next day label");

        // 地点候補 (一次細分区域名の一覧)
        check(Weather.forecastAreaNames(fc).equals(List.of("南部", "北部")), "forecast area names");

        // 台風 (A2S v0.5.0 形式: 台風ごとに1件、表示行 lines が本体): 無ければ null
        check(Weather.typhoonFromA2s(List.of()) == null, "typhoon empty null");
        Map<String, Object> tyEv = Json.obj(Json.parse("{\"kind\":\"typhoon\",\"key\":\"typhoon:10\",\"number\":\"10\",\"name\":\"台風第10号\",\"englishName\":\"SANBA\",\"lines\":[\"・台風第10号\"],\"fields\":{},\"time\":\"\"}"));
        check(A2s.isA2sEvent(tyEv), "a2s typhoon event valid");
        check(!A2s.isA2sEvent(Json.obj(Json.parse("{\"kind\":\"typhoon\",\"key\":\"typhoon:x\",\"lines\":[]}"))), "a2s typhoon without lines rejected");
        check(Weather.typhoonFromA2s(List.of(tyEv)).body().contains("台風第10号"), "typhoon line shown");
        check(Weather.typhoonFromA2s(List.of(tyEv)).body().startsWith("🌀 台風第10号"), "typhoon line marker normalized");
        // lines が無いイベントは名称・英語名から最低限の行を組む
        Map<String, Object> tyNoLines = Json.obj(Json.parse("{\"kind\":\"typhoon\",\"key\":\"typhoon:11\",\"number\":\"11\",\"name\":\"台風第11号\",\"englishName\":\"TEST\"}"));
        check(Weather.typhoonFromA2s(List.of(tyNoLines)).body().contains("台風第11号"), "typhoon fallback from name");
        // ストア: 台風も key + updatedAt で重複排除される
        A2s.EventStore tStore = new A2s.EventStore();
        Map<String, Object> tyEvUpd = new java.util.HashMap<>(tyEv);
        tyEvUpd.put("updatedAt", "2026-11-17T03:00:00.000Z");
        check(tStore.accept(tyEvUpd) && !tStore.accept(tyEvUpd) && tStore.typhoonEvents().size() == 1, "a2s typhoon store dedups");

        // 接続診断の整形
        String diag = Http.formatDiag(List.of(
                new Models.DiagResult("ソースA", "https://a", true, 123, ""),
                new Models.DiagResult("ソースB", "https://b", false, 0, "通信がタイムアウトしました")));
        check(diag.contains("○ 接続OK（123ms）： ソースA"), "diag ok line");
        check(diag.contains("× 失敗（通信がタイムアウトしました）： ソースB"), "diag fail line");

        // 通知: 新しさ判定 (1時間超は通知しない / 未来は5分まで許容)
        long base = Http.parseP2pTimeMs("2026/11/17 12:00:00");
        check(Notify.isFresh(base, base + 30 * 60 * 1000L), "notify fresh 30min");
        check(!Notify.isFresh(base, base + 61 * 60 * 1000L), "notify stale 61min");
        check(Notify.isFresh(base, base - 3 * 60 * 1000L), "notify slight future ok");
        check(!Notify.isFresh(base, base - 10 * 60 * 1000L), "notify far future ng");
        check(!Notify.isFresh(-1, base), "notify unknown time ng");

        // 通知: 地震イベントの震度しきい値
        Object nq = Json.parse("["
                + "{\"id\":\"nq1\",\"earthquake\":{\"time\":\"2026/11/17 12:00:00\",\"maxScale\":40,\"hypocenter\":{\"name\":\"テスト震源\",\"magnitude\":5.0,\"depth\":40}}},"
                + "{\"id\":\"nq2\",\"earthquake\":{\"time\":\"2026/11/17 12:05:00\",\"maxScale\":20,\"hypocenter\":{\"name\":\"小さい地震\",\"magnitude\":3.5,\"depth\":10}}}"
                + "]");
        List<Models.NotifyEvent> qev = Notify.parseQuakeEvents(nq, 30);
        check(qev.size() == 1 && qev.get(0).id().equals("quake:nq1"), "notify quake threshold");
        check(qev.get(0).title().contains("震度4"), "notify quake title");
        check(qev.get(0).occurredMs() == base, "notify quake occurred");
        check(Notify.parseQuakeEvents(nq, 45).isEmpty(), "notify quake threshold 5-");

        // 通知: 津波イベント (発表中のみ、解除は null)
        Object nt = Json.parse("[{\"id\":\"nt1\",\"cancelled\":false,\"time\":\"2026/11/17 12:00:00\",\"areas\":[{\"name\":\"宮城県沿岸\",\"grade\":\"Warning\"}]}]");
        Models.NotifyEvent tev = Notify.parseTsunamiEvent(nt);
        check(tev != null && tev.id().equals("tsunami:nt1") && tev.kind().equals("tsunami"), "notify tsunami event");
        Object ntc = Json.parse("[{\"id\":\"nt2\",\"cancelled\":true,\"time\":\"2026/11/17 12:30:00\",\"areas\":[{\"name\":\"宮城県沿岸\",\"grade\":\"Watch\"}]}]");
        check(Notify.parseTsunamiEvent(ntc) == null, "notify tsunami cancelled null");
        check(Notify.parseTsunamiEvent(Json.parse("[]")) == null, "notify tsunami empty null");

        // 通知: EEW イベント (新鮮なもののみ、取り消しは null)
        // ID はレコード ID ではなく発生時刻 (originTime) で固定される
        // base は parseP2pTimeMs (Asia/Tokyo 解釈) 由来のため、生成側も Asia/Tokyo を明示する
        // (デフォルト TZ に依存すると UTC 環境では 9 時間ずれて EEW が古いと判定される)
        java.text.SimpleDateFormat nowFmt = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN);
        nowFmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        String nowStr = nowFmt.format(new java.util.Date(base + 60_000));
        Object ne = Json.parse("[{\"id\":\"ne1\",\"time\":\"" + nowStr + "\",\"earthquake\":{\"time\":\"" + nowStr + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
        Models.NotifyEvent eev = Notify.parseEewEvent(ne, base + 60_000);
        // B-5: ID は発生時刻ベース＋推定最大震度の接尾辞 (上方修正の判定に使う)
        check(eev != null && eev.id().equals("eew:" + nowStr + "#45") && eev.kind().equals("eew"), "notify eew event");
        check(Notify.parseEewEvent(ne, base + 2 * 60 * 60 * 1000L) == null, "notify eew stale null");
        // originTime があればそちらを ID のベースに使う
        Object neOrigin = Json.parse("[{\"id\":\"ne2\",\"time\":\"" + nowStr + "\",\"earthquake\":{\"originTime\":\"2026/11/17 12:01:00\",\"time\":\"" + nowStr + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
        Models.NotifyEvent eevOrigin = Notify.parseEewEvent(neOrigin, base + 60_000);
        check(eevOrigin != null && eevOrigin.id().equals("eew:2026/11/17 12:01:00#45"), "notify eew id uses originTime");
        Object neFollow = Json.parse("[{\"id\":\"ne3\",\"time\":\"" + nowStr + "\",\"earthquake\":{\"originTime\":\"2026/11/17 12:01:00\",\"time\":\"" + nowStr + "\",\"maxScale\":50,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":50}]}]");
        Models.NotifyEvent eevFollow = Notify.parseEewEvent(neFollow, base + 60_000);
        check(eevFollow != null && eevFollow.id().equals("eew:2026/11/17 12:01:00#50"), "notify eew followup id carries scale");
        // B-5: 重複排除は EEW だけ震度対応 — 同じ・下がった続報は再通知せず、上方修正だけ再通知する
        java.util.LinkedHashSet<String> eewSeen = new java.util.LinkedHashSet<>();
        check(Notify.filterNew(List.of(eevOrigin), eewSeen).size() == 1, "eew dedup first notified");
        check(Notify.filterNew(List.of(eevOrigin), eewSeen).isEmpty(), "eew dedup same scale not renotified");
        check(Notify.filterNew(List.of(eevFollow), eewSeen).size() == 1, "eew dedup upward revision renotified");
        check(Notify.filterNew(List.of(eevOrigin), eewSeen).isEmpty(), "eew dedup downgrade after upgrade not renotified");
        // 旧形式 ID (震度なし) で通知済みの EEW は、アップデート直後も再通知しない
        java.util.LinkedHashSet<String> legacySeen = new java.util.LinkedHashSet<>(List.of("eew:2026/11/17 12:01:00"));
        check(Notify.filterNew(List.of(eevFollow), legacySeen).isEmpty(), "eew dedup legacy id treated as notified");
        Object neOther = Json.parse("[{\"id\":\"ne4\",\"time\":\"" + nowStr + "\",\"earthquake\":{\"originTime\":\"2026/11/17 12:05:00\",\"time\":\"" + nowStr + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"別の震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
        Models.NotifyEvent eevOther = Notify.parseEewEvent(neOther, base + 60_000);
        check(eevOther != null && !eevOther.id().equals(eevOrigin.id()), "notify eew different origin different id");

        // 通知: 警報イベント (A2S 形式、注意報のみは条件次第。絞り込みは severity で行う)
        // ID は「地点名 + 対象コード群のハッシュ」で、発表時刻には依存しない
        Map<String, Object> wAdv = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T12:00:00+09:00\",\"headline\":\"\",\"warnings\":[{\"code\":\"14\",\"name\":\"雷注意報\",\"status\":\"発表\",\"severity\":2}],\"maxSeverity\":2,\"released\":false}"));
        check(Notify.warningEvent(Models.TAKASAKI, wAdv, false) == null, "notify warning advisory excluded");
        Models.NotifyEvent advEv = Notify.warningEvent(Models.TAKASAKI, wAdv, true);
        check(advEv != null && advEv.id().startsWith("warning:高崎:"), "notify warning advisory included");
        check(advEv != null && advEv.occurredMs() == Http.parseJmaTimeMs("2026-11-17T12:00:00+09:00"), "notify warning occurred");
        check(Notify.warningEvent(Models.TAKASAKI, null, true) == null, "notify warning no event null");
        // 発表時刻だけが進んでも (他地域の更新)、対象警報が同じなら ID は変わらない → 再通知されない
        Map<String, Object> wAdvLater = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T12:20:00+09:00\",\"headline\":\"\",\"warnings\":[{\"code\":\"14\",\"name\":\"雷注意報\",\"status\":\"発表\",\"severity\":2}],\"maxSeverity\":2,\"released\":false}"));
        Models.NotifyEvent advEvLater = Notify.warningEvent(Models.TAKASAKI, wAdvLater, true);
        check(advEvLater != null && advEvLater.id().equals(advEv.id()), "notify warning id stable across report updates");
        // 同じ都道府県 (100000) の別地点では、対象警報が同じでも ID が衝突しない
        Models.Location maebashi = new Models.Location("前橋", 36.389, 139.063, "100000", "南部");
        Models.NotifyEvent otherLocEv = Notify.warningEvent(maebashi, wAdv, true);
        check(otherLocEv != null && otherLocEv.id().startsWith("warning:前橋:") && !otherLocEv.id().equals(advEv.id()), "notify warning id differs per location");
        // ID ヘルパー: コードの並び順に依存せず、顔ぶれが変われば変わる
        check(Notify.warningId("高崎", List.of("03", "14")).equals(Notify.warningId("高崎", List.of("14", "03"))), "warning id order independent");
        check(!Notify.warningId("高崎", List.of("03")).equals(Notify.warningId("高崎", List.of("14"))), "warning id differs by codes");
        Map<String, Object> wAlert = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T13:00:00+09:00\",\"headline\":\"\",\"warnings\":[{\"code\":\"03\",\"name\":\"大雨警報\",\"status\":\"発表\",\"severity\":3}],\"maxSeverity\":3,\"released\":false}"));
        Models.NotifyEvent alertEv = Notify.warningEvent(Models.TAKASAKI, wAlert, false);
        check(alertEv != null && alertEv.body().contains("大雨警報"), "notify warning alert included");
        check(alertEv != null && !alertEv.id().equals(advEv.id()), "notify warning id changes with warning set");
        Map<String, Object> wSpecial = Json.obj(Json.parse("{\"kind\":\"warning\",\"key\":\"warning:100000:100010\",\"officeCode\":\"100000\",\"prefName\":\"群馬県\",\"areaCode\":\"100010\",\"areaName\":\"群馬県南部\",\"reportDatetime\":\"2026-11-17T14:00:00+09:00\",\"headline\":\"\",\"warnings\":[{\"code\":\"33\",\"name\":\"大雨特別警報\",\"status\":\"発表\",\"severity\":4}],\"maxSeverity\":4,\"released\":false}"));
        Models.NotifyEvent spEv = Notify.warningEvent(Models.TAKASAKI, wSpecial, false);
        check(spEv != null && spEv.title().contains("特別警報"), "notify warning special title");

        // 通知: 重複排除 (同じイベントは2回目に出ない) と永続化の往復・上限
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<Models.NotifyEvent> evs = List.of(
                new Models.NotifyEvent("quake:a", "quake", "t", "b", base),
                new Models.NotifyEvent("quake:b", "quake", "t", "b", base));
        check(Notify.filterNew(evs, seen).size() == 2, "dedup first all new");
        check(Notify.filterNew(evs, seen).isEmpty(), "dedup second none");
        check(Notify.filterNew(List.of(evs.get(0),
                new Models.NotifyEvent("quake:c", "quake", "t", "b", base)), seen).size() == 1, "dedup mixed");
        java.util.LinkedHashSet<String> restored = Notify.parseSeenIds(Notify.joinSeenIds(seen));
        check(restored.equals(seen), "seen persist roundtrip");
        java.util.LinkedHashSet<String> big = new java.util.LinkedHashSet<>();
        for (int i = 0; i < Notify.SEEN_MAX + 50; i++) big.add("id" + i);
        java.util.LinkedHashSet<String> pruned = Notify.parseSeenIds(Notify.joinSeenIds(big));
        check(pruned.size() == Notify.SEEN_MAX && !pruned.contains("id0") && pruned.contains("id" + (Notify.SEEN_MAX + 49)), "seen prune keeps latest");

        // 天気絵文字: 降水確率50%以上なら晴れ/くもり系でも雨系にする (「降水86%なのに☀」対策)
        check(Weather.weatherEmoji(0, 86, 3).equals("☔"), "emoji rain override strong");
        check(Weather.weatherEmoji(0, 60, 15).equals("🌧"), "emoji rain override");
        check(Weather.weatherEmoji(3, 49, 15).equals("☁"), "emoji below threshold unchanged");
        check(Weather.weatherEmoji(61, 90, 3).equals("🌧"), "emoji precip code unchanged");
        // 天気絵文字: 夜間 (21時〜翌4時台) の晴れは 🌙
        check(Weather.weatherEmoji(0, 10, 23).equals("🌙"), "emoji night clear");
        check(Weather.weatherEmoji(1, 0, 2).equals("🌙"), "emoji night mostly clear");
        check(Weather.weatherEmoji(0, 10, 12).equals("☀"), "emoji day clear");
        check(Weather.weatherEmoji(2, 30, 22).equals("⛅"), "emoji night partly cloudy unchanged");
        // 夜でも降水確率が高ければ雨系が優先される
        check(Weather.weatherEmoji(0, 86, 3).equals("☔"), "emoji rain beats night");

        // 時間別の構造化データ: 実機スクショのケース (深夜3時・晴れコード・降水86% → ☔)
        long nowMs2 = java.time.ZonedDateTime.of(2026, 11, 17, 20, 20, 0, 0, java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
        Object omh2 = Json.parse("{\"hourly\":{\"time\":[\"2026-11-17T21:00\",\"2026-11-18T00:00\",\"2026-11-18T03:00\"],\"temperature_2m\":[10.0,9.0,9.0],\"precipitation_probability\":[10,20,86],\"weather_code\":[0,0,0]}}");
        List<Models.HourlyEntry> he = Weather.parseHourly(omh2, nowMs2);
        check(he.size() == 3, "hourly entries count");
        check(he.get(0).hour() == 21 && he.get(0).emoji().equals("🌙"), "hourly entry night moon");
        check(he.get(2).dateLabel().equals("11/18") && he.get(2).emoji().equals("☔") && he.get(2).popPct() == 86, "hourly entry rain override");
        check(he.get(2).weather().equals("快晴") && he.get(2).tempC() == 9.0, "hourly entry fields");
        check(Weather.formatHourly(omh2, nowMs2).contains("3時 ☔ 9℃ 降水86%"), "hourly text rain override");

        // 週間の構造化データ: 降水確率最大値も絵文字に反映する
        Object omw = Json.parse("{\"daily\":{\"time\":[\"2026-11-17\",\"2026-11-18\"],\"weather_code\":[0,0],\"temperature_2m_max\":[21.0,18.0],\"temperature_2m_min\":[9.0,8.0],\"precipitation_probability_max\":[10,90]}}");
        List<Models.DailyEntry> de = Weather.parseWeekly(omw);
        check(de.size() == 2 && de.get(0).emoji().equals("☀") && de.get(0).popPct() == 10, "weekly entry sunny");
        check(de.get(1).emoji().equals("☔") && de.get(1).popPct() == 90, "weekly entry rain override");
        check(de.get(1).minC() == 8.0 && de.get(1).maxC() == 18.0, "weekly entry temps");
        check(Weather.formatWeekly(omw).contains("11/18 ☔ 8〜18℃"), "weekly text rain override");

        // 気温が JSON null の場合は 0℃ ではなく「不明 (null → ? / —)」になること
        Object omNullH = Json.parse("{\"hourly\":{\"time\":[\"2026-11-17T15:00\"],\"temperature_2m\":[null],\"precipitation_probability\":[10],\"weather_code\":[0]}}");
        List<Models.HourlyEntry> heNull = Weather.parseHourly(omNullH, nowMs);
        check(heNull.size() == 1 && heNull.get(0).tempC() == null, "hourly null temp is unknown");
        check(Weather.formatHourly(omNullH, nowMs).contains("15時 ☀ ?℃"), "hourly null temp formatted as ?");
        Object omNullW = Json.parse("{\"daily\":{\"time\":[\"2026-11-17\"],\"weather_code\":[0],\"temperature_2m_max\":[null],\"temperature_2m_min\":[null]}}");
        List<Models.DailyEntry> deNull = Weather.parseWeekly(omNullW);
        check(deNull.size() == 1 && deNull.get(0).minC() == null && deNull.get(0).maxC() == null, "weekly null temps are unknown");
        check(Weather.formatWeekly(omNullW).contains("?〜?℃"), "weekly null temps formatted as ?");

        // 表示側の待ち上限は、取得1回の最悪ケース (25秒タイムアウト×3回 + バックオフ 1秒+2秒 = 78秒) を超えること
        // (下回ると取得できているのに「取得できませんでした」になる。C#版 v1.2.2 の不具合と同じ構図)
        check(Http.DISPLAY_WAIT_SECONDS > 25 * 3 + 1 + 2, "display wait exceeds fetch worst case");

        // 取得プールのスレッドは daemon (--cli 終了後に JVM が残らないため)
        boolean poolDaemon;
        try { poolDaemon = Http.FETCH_POOL.submit(() -> Thread.currentThread().isDaemon()).get(); }
        catch (Exception e) { poolDaemon = false; }
        check(poolDaemon, "fetch pool daemon threads");

        // B-1: 551 複数報の統合 — 同一地震の速報・続報・確定報が1件に統合され、
        // 確定報の値と報種別ラベル (確定報) が表示される
        Object qMulti = Json.parse("["
                + "{\"id\":\"m1\",\"issue\":{\"time\":\"2026/11/17 09:58:10\",\"type\":\"ScalePrompt\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"Unknown\",\"hypocenter\":{\"name\":\"\",\"magnitude\":-1,\"depth\":-999}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":30}]},"
                + "{\"id\":\"m2\",\"issue\":{\"time\":\"2026/11/17 09:59:00\",\"type\":\"ScaleAndDestination\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.5,\"depth\":40}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":40}]},"
                + "{\"id\":\"m3\",\"issue\":{\"time\":\"2026/11/17 10:02:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":30}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"前橋市\",\"scale\":40},{\"pref\":\"埼玉県\",\"addr\":\"さいたま市大宮区\",\"scale\":30}]}"
                + "]");
        List<Models.Quake> qm = Quakes.parseEarthquakes(qMulti);
        check(qm.size() == 1, "merge 551 multi-report single quake");
        check(qm.get(0).place().equals("群馬県南部"), "merge place from named report");
        check(qm.get(0).detail().contains("M4.8"), "merge magnitude from detail report");
        check(qm.get(0).detail().contains("深さ約30km"), "merge depth from detail report");
        check(qm.get(0).detail().contains("報種別： 確定報"), "merge label detail");
        check(qm.get(0).detail().contains("津波の心配なし"), "merge tsunami definite wins over unknown");
        // B-2 第2歩: 市区町村照合の地点震度 (県内最大の併記は維持)
        check(qm.get(0).detail().contains("高崎市の震度 4"), "merge city intensity takasaki");
        check(qm.get(0).detail().contains("さいたま市の震度 3"), "merge city intensity omiya ward addr");
        check(qm.get(0).detail().contains("群馬県内の最大震度 4"), "merge pref max kept");
        // B-19: 最大震度地点は登録地点の市区町村一致を優先 (先頭の前橋市ではなく高崎市)
        check(qm.get(0).detail().contains("最大震度観測: 高崎市"), "merge top addr prefers registered city");
        // 速報のみの地震は「速報」ラベル、訂正情報があれば「訂正報」ラベル
        Object qPromptOnly = Json.parse("[{\"id\":\"p1\",\"issue\":{\"time\":\"2026/11/17 09:58:10\",\"type\":\"ScalePrompt\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"Unknown\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":-1,\"depth\":-999}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qPromptOnly).get(0).detail().contains("報種別： 速報"), "merge label prompt");
        Object qCorrect = Json.parse("[{\"id\":\"c1\",\"issue\":{\"time\":\"2026/11/17 10:05:00\",\"type\":\"DetailScale\",\"correct\":\"ScaleOnly\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":30}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qCorrect).get(0).detail().contains("報種別： 訂正報"), "merge label correction");
        // 発生時刻が違えば別地震として統合しない。震源名が揺れても震央座標が近ければ統合する
        Object qTwo = Json.parse("["
                + "{\"id\":\"t1\",\"issue\":{\"time\":\"2026/11/17 09:58:10\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":30,\"latitude\":36.2,\"longitude\":139.0}},\"points\":[]},"
                + "{\"id\":\"t2\",\"issue\":{\"time\":\"2026/11/17 09:58:20\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県 南部\",\"magnitude\":4.8,\"depth\":30,\"latitude\":36.21,\"longitude\":139.01}},\"points\":[]},"
                + "{\"id\":\"t3\",\"issue\":{\"time\":\"2026/11/17 10:10:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 10:10:00\",\"maxScale\":30,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"茨城県沖\",\"magnitude\":5.0,\"depth\":50,\"latitude\":36.5,\"longitude\":141.0}},\"points\":[]}"
                + "]");
        check(Quakes.parseEarthquakes(qTwo).size() == 2, "merge same-time near-coord merged, different time separate");

        // 深さ 0km 表示の回帰: P2P は気象庁の「ごく浅い」を depth=0 で表す
        // (実例: 2026/11/20 21:46 熊本県天草・芦北地方 M4.1 最大震度4。
        //  速報 depth=-1 (震源要素すべて未確定のセンチネル) → 震源情報 depth=0
        //  → 詳細情報 depth=0 で、気象庁発表の深さは「ごく浅い」)。
        // depth=0 を「深さ約0km」と表示してはならない
        Object qShallow = Json.parse("["
                + "{\"id\":\"s1\",\"issue\":{\"time\":\"2026/11/20 21:47:33\",\"type\":\"ScalePrompt\"},\"earthquake\":{\"time\":\"2026/11/20 21:46:00\",\"maxScale\":40,\"domesticTsunami\":\"Checking\",\"hypocenter\":{\"name\":\"\",\"magnitude\":-1,\"depth\":-1,\"latitude\":-200,\"longitude\":-200}},\"points\":[{\"pref\":\"熊本県\",\"addr\":\"熊本県天草・芦北\",\"scale\":40}]},"
                + "{\"id\":\"s2\",\"issue\":{\"time\":\"2026/11/20 21:48:32\",\"type\":\"Destination\"},\"earthquake\":{\"time\":\"2026/11/20 21:46:00\",\"maxScale\":-1,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"熊本県天草・芦北地方\",\"magnitude\":4.1,\"depth\":0,\"latitude\":32.3,\"longitude\":130.5}},\"points\":[]},"
                + "{\"id\":\"s3\",\"issue\":{\"time\":\"2026/11/20 21:49:48\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/20 21:46:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"熊本県天草・芦北地方\",\"magnitude\":4.1,\"depth\":0,\"latitude\":32.3,\"longitude\":130.5}},\"points\":[{\"pref\":\"熊本県\",\"addr\":\"芦北町芦北\",\"scale\":40}]}"
                + "]");
        List<Models.Quake> qSh = Quakes.parseEarthquakes(qShallow);
        check(qSh.size() == 1, "depth shallow multi-report merged");
        check(qSh.get(0).detail().contains("深さごく浅い"), "depth 0 is very shallow not 0km");
        check(!qSh.get(0).detail().contains("深さ約0km"), "depth 0 never shown as 0km");
        // 深さ単独 (統合なし) でも depth=0 は「ごく浅い」
        Object qShallowSingle = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/20 21:46:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"熊本県天草・芦北地方\",\"magnitude\":4.1,\"depth\":0}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qShallowSingle).get(0).detail().contains("深さごく浅い"), "depth 0 single report is very shallow");
        // 統合: 主報の深さが不明センチネル (-1) でも、判明している他報の深さを優先する
        Object qDepthKnownWins = Json.parse("["
                + "{\"id\":\"k1\",\"issue\":{\"time\":\"2026/11/17 10:02:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":-1}},\"points\":[]},"
                + "{\"id\":\"k2\",\"issue\":{\"time\":\"2026/11/17 09:59:00\",\"type\":\"ScaleAndDestination\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.5,\"depth\":40}},\"points\":[]}"
                + "]");
        check(Quakes.parseEarthquakes(qDepthKnownWins).get(0).detail().contains("深さ約40km"), "merge known depth wins over primary sentinel -1");
        // 統合: 主報の深さ欠落時、途中の不明センチネル (-1) の報が後続の判明値を塞がない
        Object qDepthSentinelMiddle = Json.parse("["
                + "{\"id\":\"d1\",\"issue\":{\"time\":\"2026/11/17 10:02:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8}},\"points\":[]},"
                + "{\"id\":\"d2\",\"issue\":{\"time\":\"2026/11/17 09:59:00\",\"type\":\"ScaleAndDestination\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.5,\"depth\":-1}},\"points\":[]},"
                + "{\"id\":\"d3\",\"issue\":{\"time\":\"2026/11/17 09:58:30\",\"type\":\"Destination\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.5,\"depth\":30}},\"points\":[]}"
                + "]");
        check(Quakes.parseEarthquakes(qDepthSentinelMiddle).get(0).detail().contains("深さ約30km"), "merge sentinel report does not block later known depth");
        // 統合: 判明値が 0 (ごく浅い) の他報も「判明」として優先する (主報不明でも上書きされる)
        Object qDepthZeroWins = Json.parse("["
                + "{\"id\":\"z1\",\"issue\":{\"time\":\"2026/11/17 10:02:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":-999}},\"points\":[]},"
                + "{\"id\":\"z2\",\"issue\":{\"time\":\"2026/11/17 09:59:00\",\"type\":\"ScaleAndDestination\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.5,\"depth\":0}},\"points\":[]}"
                + "]");
        check(Quakes.parseEarthquakes(qDepthZeroWins).get(0).detail().contains("深さごく浅い"), "merge known depth 0 (very shallow) wins over unknown");
        // 深さが全報不明 (欠落・null・センチネル) なら「深さ不明」で、0km と表示しない
        Object qDepthAbsent = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"テスト震源\",\"magnitude\":4.0}},\"points\":[]}]");
        String absentDetail = Quakes.parseEarthquakes(qDepthAbsent).get(0).detail();
        check(absentDetail.contains("深さ不明") && !absentDetail.contains("深さ約0km"), "depth absent is unknown not 0km");
        Object qDepthNull = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"テスト震源\",\"magnitude\":4.0,\"depth\":null}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qDepthNull).get(0).detail().contains("深さ不明"), "depth null is unknown");

        // B-5: EEW の鮮度短縮と格下げ — 3分以内は danger、3分超は warn＋経過注記、10分超は出さない
        java.text.SimpleDateFormat eewFmt = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN);
        eewFmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
        String eewTimeStr = eewFmt.format(new java.util.Date(base));
        Object eewFresh = Json.parse("[{\"id\":\"ef1\",\"time\":\"" + eewTimeStr + "\",\"earthquake\":{\"time\":\"" + eewTimeStr + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
        check(Eew.parseEew(eewFresh, base + 60_000).level().equals("danger"), "eew fresh is danger");
        Models.Section eewAged = Eew.parseEew(eewFresh, base + 5 * 60_000L);
        check(eewAged != null && eewAged.level().equals("warn"), "eew aged downgraded to warn");
        check(eewAged != null && eewAged.body().contains("発表から約5分が経過"), "eew aged note");
        check(Eew.parseEew(eewFresh, base + 11 * 60_000L) == null, "eew over display window hidden");
        check(Notify.parseEewEvent(eewFresh, base + 11 * 60_000L) == null, "eew notify over display window null");

        // A-4: Open-Meteo は複合モデルを明示指定し、接尾辞付きキーから読む
        check(Weather.openMeteoUrl(Models.TAKASAKI).contains("&models=jma_seamless,ecmwf_ifs025"), "open-meteo models param order");
        check(Weather.OPEN_METEO_SOURCE_NOTE.contains("気象庁モデル MSM/GSM") && Weather.OPEN_METEO_SOURCE_NOTE.contains("ECMWF IFS"), "open-meteo source note");
        // 降水確率は ECMWF 側キーから読む (JMA 側キーは null だらけでも影響しない)
        Object omSfx = Json.parse("{\"hourly\":{\"time\":[\"2026-11-17T15:00\"],\"temperature_2m_jma_seamless\":[12.4],\"precipitation_probability_jma_seamless\":[null],\"precipitation_probability_ecmwf_ifs025\":[73],\"weather_code_jma_seamless\":[61]},"
                + "\"daily\":{\"time\":[\"2026-11-17\"],\"weather_code_jma_seamless\":[61],\"temperature_2m_max_jma_seamless\":[20.0],\"temperature_2m_min_jma_seamless\":[10.0],\"precipitation_probability_max_jma_seamless\":[null],\"precipitation_probability_max_ecmwf_ifs025\":[88]}}");
        List<Models.HourlyEntry> heSfx = Weather.parseHourly(omSfx, nowMs);
        check(heSfx.size() == 1 && heSfx.get(0).popPct() == 73 && heSfx.get(0).tempC() == 12.4, "open-meteo hourly suffixed keys");
        List<Models.DailyEntry> deSfx = Weather.parseWeekly(omSfx);
        check(deSfx.size() == 1 && deSfx.get(0).popPct() == 88 && deSfx.get(0).maxC() == 20.0, "open-meteo daily suffixed keys pop from ecmwf");

        // ブリーフィングキャッシュのサイズ上限 (100KB、UTF-8 バイト数で判定)
        check(UiFormat.briefingCacheFits("短いキャッシュ"), "cache small fits");
        check(UiFormat.briefingCacheFits("a".repeat(100 * 1024)), "cache at limit fits");
        check(!UiFormat.briefingCacheFits("a".repeat(100 * 1024 + 1)), "cache over limit rejected");
        check(!UiFormat.briefingCacheFits("あ".repeat(40_000)), "cache multibyte counted as bytes"); // 40,000字 = 120,000 bytes

        // B-14: 津波区分の安全側判定 — None のみ「心配なし」、未知値は「確認中」
        Object qTsuNone = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"テスト\",\"magnitude\":4.0,\"depth\":40}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qTsuNone).get(0).detail().contains("津波の心配なし"), "tsunami None is no-worry");
        Object qTsuUnknownVal = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"SomethingNew\",\"hypocenter\":{\"name\":\"テスト\",\"magnitude\":4.0,\"depth\":40}},\"points\":[]}]");
        String tsuUnknownDetail = Quakes.parseEarthquakes(qTsuUnknownVal).get(0).detail();
        check(tsuUnknownDetail.contains("津波の有無は確認中") && !tsuUnknownDetail.contains("心配なし"), "tsunami unknown value is checking not no-worry");
        Object qTsuChecking = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"Checking\",\"hypocenter\":{\"name\":\"テスト\",\"magnitude\":4.0,\"depth\":40}},\"points\":[]}]");
        check(Quakes.parseEarthquakes(qTsuChecking).get(0).detail().contains("津波の有無は確認中"), "tsunami Checking is checking");

        // A-11/A-14: 降水量の取得・表示と、時間別の区間最大
        Object omPrecip = Json.parse("{\"hourly\":{\"time\":[\"2026-11-17T15:00\",\"2026-11-17T16:00\",\"2026-11-17T17:00\",\"2026-11-17T18:00\"],\"temperature_2m\":[12.0,11.0,10.0,10.0],\"precipitation_probability\":[20,55,80,30],\"weather_code\":[61,61,61,3],\"precipitation\":[1.5,2.0,0.5,0.0]},"
                + "\"daily\":{\"time\":[\"2026-11-17\"],\"weather_code\":[61],\"temperature_2m_max\":[15.0],\"temperature_2m_min\":[8.0],\"precipitation_probability_max\":[80],\"precipitation_sum\":[12.5]}}");
        List<Models.HourlyEntry> hePrecip = Weather.parseHourly(omPrecip, nowMs);
        check(hePrecip.size() == 2 && hePrecip.get(0).precipMm() == 1.5, "hourly precipitation parsed");
        check(hePrecip.get(0).popIntervalMax() == 80, "hourly interval max covers 3h peak");
        check(hePrecip.get(0).popPct() == 20, "hourly sampled pop kept");
        check(Weather.formatHourly(omPrecip, nowMs).contains("（3時間最大80%）"), "hourly interval max shown");
        check(Weather.formatHourly(omPrecip, nowMs).contains("降水量1.5mm"), "hourly precipitation shown");
        List<Models.DailyEntry> dePrecip = Weather.parseWeekly(omPrecip);
        check(dePrecip.size() == 1 && dePrecip.get(0).precipMm() == 12.5, "daily precipitation parsed");
        check(Weather.formatWeekly(omPrecip).contains("降水量12.5mm"), "daily precipitation shown");
        check(Weather.openMeteoUrl(Models.TAKASAKI).contains("precipitation") && Weather.openMeteoUrl(Models.TAKASAKI).contains("precipitation_sum"), "open-meteo url requests precipitation");

        // A-13: カスタム地点の座標検証
        check(Geo.validateLocation(36.32, 139.00, "100000").isEmpty(), "validate takasaki ok");
        check(Geo.validateLocation(35.91, 139.62, "110000").isEmpty(), "validate omiya ok");
        check(!Geo.validateLocation(91, 139.0, "100000").isEmpty(), "validate lat out of range");
        check(!Geo.validateLocation(36.0, 181.0, "100000").isEmpty(), "validate lon out of range");
        check(Geo.validateLocation(10.0, 100.0, "100000").stream().anyMatch(s -> s.contains("日本域外")), "validate outside japan warns");
        check(Geo.validateLocation(35.68, 139.69, "100000").stream().anyMatch(s -> s.contains("東京都")), "validate pref mismatch warns");
        check(Geo.validateLocation(43.06, 141.35, "016000").isEmpty(), "validate sapporo hokkaido ok");

        // C-4: 気象庁ナウキャストのタイル URL と targetTimes の解釈
        check(Radar.jmaNowcastTileUrl("20261122010000", "20261122010000", 8, 227, 100)
                .equals("https://www.jma.go.jp/bosai/jmatile/data/nowc/20261122010000/none/20261122010000/surf/hrpns/8/227/100.png"), "jma nowcast tile url");
        Object tt = Json.parse("[{\"basetime\":\"20261122010000\",\"validtime\":\"20261122010000\",\"elements\":[\"hrpns\",\"hrpns_nd\"]},{\"basetime\":\"20261122005500\",\"validtime\":\"20261122005500\",\"elements\":[\"hrpns\"]}]");
        Models.JmaNowcastFrame jf = Radar.parseTargetTimes(tt);
        check(jf != null && jf.basetime().equals("20261122010000") && jf.validtime().equals("20261122010000"), "jma targetTimes latest");
        check(Radar.parseTargetTimes(Json.parse("[{\"basetime\":\"x\",\"validtime\":\"y\",\"elements\":[\"other\"]}]")) == null, "jma targetTimes without hrpns null");
        check(Radar.JMA_NOWCAST_NOTE.contains("z8") && Radar.RAINVIEWER_RESOLUTION_NOTE.contains("z7"), "radar resolution notes");

        // C-7: タイル部分欠落の注記
        check(Radar.tileCoverageNote("レーダー", 4, 4).isEmpty(), "tile coverage full no note");
        check(Radar.tileCoverageNote("レーダー", 4, 3).contains("3/4枚"), "tile coverage partial note");
        check(Radar.tileCoverageNote("地図", 9, 0).contains("0/9枚"), "tile coverage zero note");

        // v1.7.0: 最寄り震度の廃止 — 市区町村の実測が無い地点は、最寄り観測点の
        // 実測に基づく推定を表示せず「実測なし」とする (市区町村照合の表示は維持)
        Object qNear = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":40}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"前橋市\",\"scale\":40},{\"pref\":\"埼玉県\",\"addr\":\"熊谷市\",\"scale\":30}]}]");
        List<Models.Quake> qn2 = Quakes.parseEarthquakes(qNear, Models.LOCATIONS);
        check(!qn2.get(0).detail().contains("最寄り"), "nearest intensity abolished: no nearest wording");
        check(!qn2.get(0).detail().contains("高崎の震度（推定）"), "nearest intensity abolished: no estimated takasaki intensity");
        check(qn2.get(0).detail().contains("高崎の震度: 実測なし"), "unmeasured location shows measured-none only");
        check(qn2.get(0).detail().contains("群馬県内の最大震度 4"), "nearest abolition keeps pref max display");
        check(qn2.get(0).detail().contains("観測点情報（実測）"), "observation points info still present");
        // 座標に結び付かない観測点しか無い場合も最寄り表示は付けない
        Object qFar = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"遠方\",\"magnitude\":4.0,\"depth\":40}},\"points\":[{\"pref\":\"北海道\",\"addr\":\"稚内市\",\"scale\":30}]}]");
        check(!Quakes.parseEarthquakes(qFar, Models.LOCATIONS).get(0).detail().contains("最寄り"), "nearest station absent after abolition");
        // 受け入れ実例: 熊本県天草・芦北地方 M2.3 の遠方地震。旧表示では数百km離れた
        // 球磨村渡の実測を根拠に高崎・大宮の震度1（実測ベース／推定）が出ていたが、
        // 廃止後は両地点とも地点震度を出さず「実測なし」になる (観測点情報は残る)
        Object qKumamoto = Json.parse("[{\"earthquake\":{\"time\":\"2026/11/22 09:58:00\",\"maxScale\":10,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"熊本県天草・芦北地方\",\"magnitude\":2.3,\"depth\":10}},\"points\":[{\"pref\":\"熊本県\",\"addr\":\"球磨村渡\",\"scale\":10},{\"pref\":\"熊本県\",\"addr\":\"天草市\",\"scale\":10}]}]");
        String kumaDetail = Quakes.parseEarthquakes(qKumamoto, Models.LOCATIONS).get(0).detail();
        check(kumaDetail.contains("高崎の震度: 実測なし"), "far kumamoto quake: takasaki measured-none");
        check(kumaDetail.contains("大宮の震度: 実測なし"), "far kumamoto quake: omiya measured-none");
        check(!kumaDetail.contains("実測ベース"), "far kumamoto quake: no measured-based classification");
        check(!kumaDetail.contains("最寄り"), "far kumamoto quake: no nearest wording");
        check(!kumaDetail.contains("高崎の震度（推定）") && !kumaDetail.contains("大宮の震度（推定）"), "far kumamoto quake: no estimated location intensity");
        check(kumaDetail.contains("観測点情報（実測）"), "far kumamoto quake: observation points info still present");

        // v1.6.0: 観測点情報と実測値 (DetailScale の points のみが観測点データ)
        Object qObs = Json.parse("[{\"id\":\"o1\",\"issue\":{\"time\":\"2026/11/17 10:02:00\",\"type\":\"DetailScale\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":45,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":30}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":40},{\"pref\":\"埼玉県\",\"addr\":\"熊谷市\",\"scale\":45},{\"pref\":\"群馬県\",\"addr\":\"前橋市\",\"scale\":30}]}]");
        String obsDetail = Quakes.parseEarthquakes(qObs).get(0).detail();
        check(obsDetail.contains("観測点情報（実測）: 全3地点"), "obs info total count");
        check(obsDetail.contains("最大震度観測地点 熊谷市: 実測震度 5弱"), "obs info max point and measured scale");
        check(obsDetail.contains("主要観測点（実測）: 熊谷市: 実測震度 5弱、高崎市: 実測震度 4、前橋市: 実測震度 3"), "obs info top points sorted by scale");
        // 震度速報のみ (points は地域単位) では観測点情報を出さない
        Object qPromptPts = Json.parse("[{\"id\":\"pp1\",\"issue\":{\"time\":\"2026/11/17 09:58:10\",\"type\":\"ScalePrompt\"},\"earthquake\":{\"time\":\"2026/11/17 09:58:00\",\"maxScale\":30,\"domesticTsunami\":\"Unknown\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":-1,\"depth\":-1}},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"群馬県南部\",\"scale\":30}]}]");
        check(Quakes.parseEarthquakes(qPromptPts).get(0).detail().contains("観測点情報なし"), "obs info none for scale prompt only");
        check(Quakes.parseEarthquakes(qPromptPts).get(0).detail().contains("推定情報（速報段階）"), "prompt stage marked as estimate");
        // points が無い地震も観測点情報なし
        check(Quakes.parseEarthquakes(qDepthAbsent).get(0).detail().contains("観測点情報なし"), "obs info none when no points");
        // ヘルパー単体: 上位件数の制限と震度降順
        check(Quakes.topObservationPoints(Quakes.detailPointsOf(List.of(Json.obj(Json.parse("{\"issue\":{\"type\":\"DetailScale\"},\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":40},{\"pref\":\"群馬県\",\"addr\":\"前橋市\",\"scale\":30}]}")))), 1)
                .equals(List.of("高崎市: 実測震度 4")), "top observation points limit");
        check(Quakes.observationPointsNote(List.of(), 40).startsWith("観測点情報なし"), "obs note empty is none");

        // v1.6.0: 地点ごとの実測/推定の区別
        // 市区町村が一致する観測点がある地点は「実測」
        check(obsDetail.contains("高崎市の震度 4（実測）"), "city intensity marked measured");
        check(obsDetail.contains("群馬県内の最大震度 4（実測）"), "pref max marked measured");
        // 実測が無い地点は数値を作らず「実測なし」とだけ示す (推定材料の併記も廃止)
        String farDetail = Quakes.parseEarthquakes(qFar, Models.LOCATIONS).get(0).detail();
        check(farDetail.contains("高崎の震度: 実測なし"), "no-measured location shows measured-none");
        check(!farDetail.contains("推定材料"), "no-measured location has no estimate materials");

        // v1.6.0: EEW の推定情報 (推定震源・推定マグニチュード・推定深さ・地域別予想震度)
        Object eewFull = Json.parse("[{\"id\":\"efull\",\"time\":\"" + eewTimeStr + "\",\"earthquake\":{\"time\":\"" + eewTimeStr + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\",\"magnitude\":5.0,\"depth\":40}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45},{\"pref\":\"埼玉県\",\"name\":\"埼玉県北部\",\"scale\":40}]}]");
        Models.Section eewFullSec = Eew.parseEew(eewFull, base + 60_000);
        check(eewFullSec != null && eewFullSec.body().contains("推定震源 テスト震源"), "eew estimated hypocenter labelled");
        check(eewFullSec != null && eewFullSec.body().contains("推定マグニチュード M5.0"), "eew estimated magnitude");
        check(eewFullSec != null && eewFullSec.body().contains("推定深さ 約40km"), "eew estimated depth");
        check(eewFullSec != null && eewFullSec.body().contains("地域別予想震度（推定）: 群馬県南部 震度5弱、埼玉県北部 震度4"), "eew regional estimated scales");
        check(eewFullSec != null && eewFullSec.body().contains("すべて推定値です"), "eew estimate disclaimer");
        check(eewFullSec != null && eewFullSec.body().contains("推定最大震度 5弱"), "eew estimated max scale labelled");

        // ===== v1.8.0: リトライ共通化 (v1.11.0 で EEW の直結経路は A2S へ一本化) =====
        // リトライ分岐 (試行そのものを差し替えてネットワークなしで検証する)
        {
          try {
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            String okRetry = Http.withRetry(() -> {
                if (attempts.incrementAndGet() < 3) throw new java.io.IOException("simulated network error");
                return "ok";
            }, new long[]{0, 0});
            check(okRetry.equals("ok") && attempts.get() == 3, "retry succeeds after network failures");
            // 404 は即失敗 (リトライしない)
            attempts.set(0);
            boolean threw404 = false;
            try {
                Http.withRetry(() -> { attempts.incrementAndGet(); throw new Http.HttpStatusException(404); }, new long[]{0, 0});
            } catch (Http.HttpStatusException e) { threw404 = e.status == 404; }
            check(threw404 && attempts.get() == 1, "retry does not retry 4xx");
            // 503 はリトライして成功する
            attempts.set(0);
            String ok503 = Http.withRetry(() -> {
                if (attempts.incrementAndGet() < 3) throw new Http.HttpStatusException(503);
                return "ok";
            }, new long[]{0, 0});
            check(ok503.equals("ok") && attempts.get() == 3, "retry retries 5xx");
            // 全回失敗したら最後の例外を投げる
            attempts.set(0);
            boolean threwLast = false;
            try {
                Http.withRetry(() -> { attempts.incrementAndGet(); throw new java.io.IOException("down"); }, new long[]{0, 0});
            } catch (java.io.IOException e) { threwLast = true; }
            check(threwLast && attempts.get() == 3, "retry gives up after max attempts");
            // delays の要素数がリトライ回数 (1要素なら試行は2回)
            attempts.set(0);
            try { Http.withRetry(() -> { attempts.incrementAndGet(); throw new java.io.IOException("down"); }, new long[]{0}); }
            catch (java.io.IOException ignored) {}
            check(attempts.get() == 2, "retry attempts follow delays length");
          } catch (Exception e) { check(false, "retry block threw: " + e); }
        }

        // ===== EEW 内部共通形式のパース (A2S 変換済み item が通る経路) =====
        // 内部共通形式: isCancel / isWarn / isFinal / serial / eventId / isAssumption を
        // item 直下に持ち、areas は scale (下限) / scaleTo (上限、99 は「〜程度以上」)
        long eewBaseMs = Http.parseP2pTimeMs("2026/11/22 17:43:49");
        String eewItemJson = "[{\"id\":\"ew1\",\"time\":\"2026/11/22 17:43:49\",\"isCancel\":false,\"isWarn\":true,\"isFinal\":false,"
                + "\"serial\":3,\"eventId\":\"20261122174253\",\"isAssumption\":true,"
                + "\"earthquake\":{\"time\":\"2026/11/22 17:42:53\",\"originTime\":\"2026/11/22 17:42:53\",\"maxScale\":45,"
                + "\"hypocenter\":{\"name\":\"群馬県南部\",\"latitude\":36.3219,\"longitude\":139.0023,\"depth\":40,\"magnitude\":6.0}},"
                + "\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45,\"scaleTo\":99,\"arrivalTime\":\"2026/11/22 17:45:01\"},"
                + "{\"pref\":\"埼玉県\",\"name\":\"埼玉県北部\",\"scale\":40,\"scaleTo\":45}]}]";
        Object eewItemRoot = Json.parse(eewItemJson);
        Map<String, Object> eewItem = Json.obj(Json.arr(eewItemRoot).get(0));
        long nowEew = eewBaseMs + 60_000;
        check(Eew.isWarn(eewItem) && !Eew.cancelled(eewItem), "eew internal is warn not cancelled");
        check(Eew.estScale(eewItem) == 45, "eew internal est scale");
        check(Eew.serial(eewItem) == 3 && Eew.isAssumption(eewItem), "eew internal serial and assumption");
        check(Eew.baseKeyOfItem(eewItem).equals("eew-2026/11/22 17:42:53@36.3,139.0"), "eew base key matches a2s rule");
        check(Eew.keyOfItem(eewItem).equals("eew-2026/11/22 17:42:53@36.3,139.0"), "eew key without cancel suffix");
        Models.Section secEew = Eew.parseEew(eewItemRoot, nowEew);
        check(secEew != null && secEew.title().equals("🚨 緊急地震速報（警報）") && secEew.level().equals("danger"), "eew internal section warn title");
        check(secEew != null && secEew.body().contains("推定最大震度 5弱"), "eew internal body est scale");
        check(secEew != null && secEew.body().contains("群馬県南部 震度5弱程度以上"), "eew area scale range open-ended from scaleTo 99");
        check(secEew != null && secEew.body().contains("埼玉県北部 震度4〜5弱"), "eew area scale range from scaleTo");
        check(secEew != null && secEew.body().contains("第3報") && secEew.body().contains("仮定震源要素による推定"), "eew internal body serial and assumption");
        check(Eew.scaleRangeText(45, -1).equals("震度5弱") && Eew.scaleRangeText(45, 45).equals("震度5弱")
                && Eew.scaleRangeText(40, 45).equals("震度4〜5弱") && Eew.scaleRangeText(45, 99).equals("震度5弱程度以上"),
                "eew scale range text variants");
        Models.NotifyEvent evEew = Notify.parseEewEvent(eewItemRoot, nowEew);
        check(evEew != null && evEew.id().equals("eew:2026/11/22 17:42:53#45"), "eew internal notify id");
        check(evEew != null && evEew.title().contains("（警報）"), "eew internal notify title shows warn");
        // 到達予想: areas の arrivalTime をそのまま使う。無い地域は S波推定
        List<Models.EewArrival> arrEew = Eew.parseArrivals(eewItemRoot, Models.LOCATIONS, nowEew);
        check(arrEew.size() == 2 && !arrEew.get(0).estimated()
                && arrEew.get(0).arrivalMs() == Http.parseP2pTimeMs("2026/11/22 17:45:01"), "eew arrivals use arrivalTime");
        check(arrEew.size() == 2 && arrEew.get(1).estimated(), "eew arrival estimated when no area time");
        // 地点別予想震度 (localForecasts 相当): 地域区分名の一致を優先し、
        // 無ければ同一都道府県の最大値を導出する
        Map<String, Integer> lf = Eew.localForecastScales(eewItem, Models.LOCATIONS);
        check(lf.getOrDefault("高崎", -1) == 45 && lf.getOrDefault("大宮", -1) == 40, "eew local forecasts derived from areaScales");
        // 取消: 表示は取消セクション、通知は null、同一視キーは -cancel
        Object eewCancelRoot = Json.parse(eewItemJson.replace("\"isCancel\":false", "\"isCancel\":true"));
        Models.Section secEewC = Eew.parseEew(eewCancelRoot, nowEew);
        check(secEewC != null && secEewC.level().equals("info") && secEewC.body().contains("取り消されました"), "eew cancelled section");
        check(Notify.parseEewEvent(eewCancelRoot, nowEew) == null, "eew cancelled notify null");
        check(Eew.keyOfItem(Json.obj(Json.arr(eewCancelRoot).get(0))).endsWith("-cancel"), "eew cancelled key");
        // 最終報 (isFinal) は本文に明示される
        Object eewFinalRoot = Json.parse(eewItemJson.replace("\"isFinal\":false", "\"isFinal\":true"));
        Models.Section secEewF = Eew.parseEew(eewFinalRoot, nowEew);
        check(secEewF != null && secEewF.body().contains("（最終報）"), "eew final flag shown");

        // ===== EEW 行動文言の震度別出し分け (v1.9.0 追記、境界 45/40/30/不明×警報・予報) =====
        check(Eew.actionGuide(45, false).equals("強い揺れに備え、頭を守り安全な場所へ移動してください。"), "eew guide 45 strong");
        check(Eew.actionGuide(70, true).equals("強い揺れに備え、頭を守り安全な場所へ移動してください。"), "eew guide 70 strong");
        check(Eew.actionGuide(40, false).equals("やや強い揺れに備えてください。落下物に注意し、頭を守ってください。"), "eew guide 40 middle");
        check(Eew.actionGuide(40, true).equals("やや強い揺れに備えてください。落下物に注意し、頭を守ってください。"), "eew guide 40 warn middle");
        check(Eew.actionGuide(30, false).equals("揺れに注意してください。"), "eew guide 30 caution");
        check(Eew.actionGuide(30, true).equals("揺れに注意してください。"), "eew guide 30 caution even if warn");
        check(Eew.actionGuide(10, false).equals("揺れに注意してください。"), "eew guide 10 caution");
        check(Eew.actionGuide(-1, true).equals("強い揺れに備え、頭を守り安全な場所へ移動してください。"), "eew guide unknown warn strong");
        check(Eew.actionGuide(-1, false).equals("揺れに注意してください。"), "eew guide unknown forecast caution");
        // セクション経由でも、震度3の EEW には強い文言が出ないこと (鮮度内・danger 経路)
        {
            java.text.SimpleDateFormat gFmt = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN);
            gFmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            String gTime = gFmt.format(new java.util.Date(base));
            Object eewGuide30 = Json.parse("[{\"id\":\"eg30\",\"time\":\"" + gTime + "\",\"earthquake\":{\"time\":\"" + gTime + "\",\"maxScale\":30,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":30}]}]");
            Models.Section gSec = Eew.parseEew(eewGuide30, base + 60_000);
            check(gSec != null && gSec.body().contains("揺れに注意してください。") && !gSec.body().contains("強い揺れに備え"), "eew section guide scale30 caution");
            Object eewGuide45 = Json.parse("[{\"id\":\"eg45\",\"time\":\"" + gTime + "\",\"earthquake\":{\"time\":\"" + gTime + "\",\"maxScale\":45,\"hypocenter\":{\"name\":\"テスト震源\"}},\"areas\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}]");
            Models.Section gSec45 = Eew.parseEew(eewGuide45, base + 60_000);
            check(gSec45 != null && gSec45.body().contains("強い揺れに備え、頭を守り安全な場所へ移動してください。"), "eew section guide scale45 strong");
        }

        // ===== v1.11.0: A2S (Aegis Alert System) 完全一本化 =====
        // バージョン定数
        check(Models.VERSION.equals("1.0.0"), "version restarted at 1.0.0");
        long a2sBase = Http.parseP2pTimeMs("2026/11/26 08:00:00");

        // 設定: URL とトークンの両方が無ければ無効 (既定は無効)。片方だけでも無効
        {
            String prevUrl = A2s.prefUrl, prevToken = A2s.prefToken;
            A2s.prefUrl = ""; A2s.prefToken = "";
            check(!A2s.enabled(), "a2s disabled by default");
            A2s.prefUrl = "https://a2s.example.net/";
            check(!A2s.enabled(), "a2s disabled with url only");
            check(A2s.baseUrl().equals("https://a2s.example.net"), "a2s base url trims trailing slash");
            A2s.prefUrl = ""; A2s.prefToken = "tok";
            check(!A2s.enabled(), "a2s disabled with token only");
            A2s.prefUrl = "https://a2s.example.net"; A2s.prefToken = "tok";
            check(A2s.enabled(), "a2s enabled with url and token");
            // 無効時はストリームを開始しない
            A2s.prefUrl = ""; A2s.prefToken = "";
            A2s.startStream(ev -> {});
            check(!A2s.streamRunning(), "a2s stream not started while disabled");
            A2s.prefUrl = prevUrl; A2s.prefToken = prevToken;
        }

        // A2S 地震イベント (実形式: A2S types.ts の QuakeEvent)
        String a2sQuakeJson = "{\"kind\":\"quake\",\"key\":\"551:2026/11/26 08:00:00|@36.3,139.0\","
                + "\"source\":\"p2p\",\"sources\":[\"p2p\"],\"receivedAt\":\"2026-11-26T08:01:00.000Z\",\"updatedAt\":\"2026-11-26T08:02:00.000Z\","
                + "\"telegramId\":\"tg-a2s-1\",\"time\":\"2026/11/26 08:00:00\",\"announcedTime\":\"2026/11/26 08:02:00\","
                + "\"hypocenter\":\"群馬県南部\",\"magnitude\":4.8,\"depthKm\":30,\"maxScale\":40,\"tsunamiKind\":\"None\",\"foreign\":false,"
                + "\"issueType\":\"DetailScale\",\"issueCorrect\":\"\",\"lat\":36.3,\"lon\":139.0,\"topPoint\":\"高崎市\",\"topPointIsArea\":false,"
                + "\"prefScales\":[{\"pref\":\"群馬県\",\"scale\":40}],"
                + "\"points\":[{\"pref\":\"群馬県\",\"name\":\"高崎市\",\"scale\":40,\"isArea\":false},{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":40,\"isArea\":true}]}";
        Map<String, Object> a2sQuakeEv = Json.obj(Json.parse(a2sQuakeJson));
        check(A2s.isA2sEvent(a2sQuakeEv), "a2s quake event valid");
        check(!A2s.isA2sEvent(Json.obj(Json.parse("{\"kind\":\"quake\",\"key\":\"x\"}"))), "a2s incomplete quake rejected");
        check(!A2s.isA2sEvent(Json.obj(Json.parse("{\"kind\":\"unknown\",\"key\":\"x\"}"))), "a2s unknown kind rejected");

        // 変換: 地震 → 551 相当 item。地域代表値 (isArea) も印を保持して取り込む
        // (v1.11.0 の欠落修正: 落とすと震度速報段階で県別最大震度が出ない)
        Map<String, Object> a2sQuakeItem = A2s.toQuakeItem(a2sQuakeEv);
        check(Json.str(a2sQuakeItem.get("id")).equals("tg-a2s-1"), "a2s quake item id is telegram id");
        check(Json.arr(a2sQuakeItem.get("points")).size() == 2, "a2s quake area points included");
        check(Json.str(Json.obj(Json.arr(a2sQuakeItem.get("points")).get(0)).get("addr")).equals("高崎市"), "a2s quake point name to addr");
        check(Json.bool(Json.obj(Json.arr(a2sQuakeItem.get("points")).get(1)).get("isArea")), "a2s quake area point keeps isArea flag");
        check(Json.arr(a2sQuakeItem.get("prefScales")).size() == 1
                && Json.dbl(Json.obj(Json.arr(a2sQuakeItem.get("prefScales")).get(0)).get("scale"), 0) == 40, "a2s quake prefScales carried");
        // DetailScale でも地域点は観測点情報 (実測) に数えない
        List<Models.Quake> a2sDetail = Quakes.parseEarthquakes(List.of(a2sQuakeItem));
        check(a2sDetail.get(0).detail().contains("観測点情報（実測）: 全1地点"), "a2s detail obs count excludes area points");
        // 深さ null は不明 (-1)、マグニチュード null も不明として変換される
        Map<String, Object> a2sQuakeNull = A2s.toQuakeItem(Json.obj(Json.parse(a2sQuakeJson.replace("\"depthKm\":30", "\"depthKm\":null").replace("\"magnitude\":4.8", "\"magnitude\":null"))));
        Map<String, Object> a2sNullHypo = Json.obj(Json.obj(a2sQuakeNull.get("earthquake")).get("hypocenter"));
        check(Json.dbl(a2sNullHypo.get("depth"), -999) == -1 && Json.dbl(a2sNullHypo.get("magnitude"), -999) == -1, "a2s quake null depth/magnitude are unknown");

        // マージ: 同じ地震の 551 相当 item 同士は1件に統合される
        Object a2sDirectRoot = Json.parse("[{\"id\":\"tg-a2s-1\",\"issue\":{\"time\":\"2026/11/26 08:02:00\",\"type\":\"DetailScale\"},"
                + "\"earthquake\":{\"time\":\"2026/11/26 08:00:00\",\"maxScale\":40,\"domesticTsunami\":\"None\",\"hypocenter\":{\"name\":\"群馬県南部\",\"magnitude\":4.8,\"depth\":30,\"latitude\":36.3,\"longitude\":139.0}},"
                + "\"points\":[{\"pref\":\"群馬県\",\"addr\":\"高崎市\",\"scale\":40}]}]");
        List<Object> a2sMergedRoot = new ArrayList<>(Json.arr(a2sDirectRoot));
        a2sMergedRoot.add(a2sQuakeItem);
        List<Models.Quake> a2sMerged = Quakes.parseEarthquakes(a2sMergedRoot);
        check(a2sMerged.size() == 1, "a2s quake merged with same telegram item");
        check(a2sMerged.get(0).detail().contains("高崎市の震度 4（実測）"), "a2s merged quake keeps city intensity");
        // A2S 由来だけで表示できる (唯一の経路)
        List<Models.Quake> a2sOnly = Quakes.parseEarthquakes(List.of(a2sQuakeItem));
        check(a2sOnly.size() == 1 && a2sOnly.get(0).place().equals("群馬県南部") && a2sOnly.get(0).maxScale() == 40, "a2s quake standalone parses");

        // 震度速報段階 (観測点なし・地域点のみ) でも県別最大震度が直結時と同じに出る
        String a2sPromptJson = a2sQuakeJson.replace("\"issueType\":\"DetailScale\"", "\"issueType\":\"ScalePrompt\"")
                .replace("\"prefScales\":[{\"pref\":\"群馬県\",\"scale\":40}]",
                        "\"prefScales\":[{\"pref\":\"群馬県\",\"scale\":40},{\"pref\":\"埼玉県\",\"scale\":30}]")
                .replace("\"points\":[{\"pref\":\"群馬県\",\"name\":\"高崎市\",\"scale\":40,\"isArea\":false},{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":40,\"isArea\":true}]",
                        "\"points\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":40,\"isArea\":true},{\"pref\":\"埼玉県\",\"name\":\"埼玉県北部\",\"scale\":30,\"isArea\":true}]");
        List<Models.Quake> a2sPrompt = Quakes.parseEarthquakes(List.of(A2s.toQuakeItem(Json.obj(Json.parse(a2sPromptJson)))));
        check(a2sPrompt.size() == 1 && a2sPrompt.get(0).detail().contains("群馬県内の最大震度 4"), "a2s prompt-only quake shows gunma pref max");
        check(a2sPrompt.get(0).detail().contains("埼玉県内の最大震度 3"), "a2s prompt-only quake shows saitama pref max");
        check(a2sPrompt.get(0).detail().contains("観測点情報なし"), "a2s prompt-only quake has no observation points");
        check(!a2sPrompt.get(0).detail().contains("高崎市の震度"), "a2s prompt-only quake has no city intensity");
        // points が空で prefScales だけでも県別最大震度が出る (地域点を prefScales から補う)
        String a2sPrefOnlyJson = a2sPromptJson.replace(
                "\"points\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":40,\"isArea\":true},{\"pref\":\"埼玉県\",\"name\":\"埼玉県北部\",\"scale\":30,\"isArea\":true}]",
                "\"points\":[]");
        Map<String, Object> a2sPrefOnlyItem = A2s.toQuakeItem(Json.obj(Json.parse(a2sPrefOnlyJson)));
        check(Json.arr(a2sPrefOnlyItem.get("points")).size() == 2, "a2s prefScales-only quake synthesizes area points");
        List<Models.Quake> a2sPrefOnly = Quakes.parseEarthquakes(List.of(a2sPrefOnlyItem));
        check(a2sPrefOnly.get(0).detail().contains("群馬県内の最大震度 4") && a2sPrefOnly.get(0).detail().contains("埼玉県内の最大震度 3"),
                "a2s prefScales-only quake shows pref maxes");
        // 国外地震: originTime (発生時刻の原表記) と tsunamiText (電文コメント) を落とさない
        String a2sForeignJson = "{\"kind\":\"quake\",\"key\":\"551:2026/11/26 09:00:00|foreign\",\"source\":\"p2p\",\"sources\":[\"p2p\"],"
                + "\"receivedAt\":\"2026-11-26T09:01:00.000Z\",\"updatedAt\":\"2026-11-26T09:01:00.000Z\",\"telegramId\":\"tg-foreign-1\","
                + "\"time\":\"2026/11/26 09:00:00\",\"announcedTime\":\"2026/11/26 09:01:00\",\"originTime\":\"2026/11/26 08:55:00\","
                + "\"hypocenter\":\"チリ沖\",\"magnitude\":7.0,\"depthKm\":null,\"maxScale\":-1,\"tsunamiKind\":\"Unknown\","
                + "\"tsunamiText\":\"チリ沖の地震で津波の調査中\",\"foreign\":true,\"issueType\":\"Foreign\",\"issueCorrect\":\"\","
                + "\"lat\":-30.0,\"lon\":-70.0,\"prefScales\":[],\"points\":[]}";
        Map<String, Object> a2sForeignItem = A2s.toQuakeItem(Json.obj(Json.parse(a2sForeignJson)));
        check(Json.str(Json.obj(a2sForeignItem.get("earthquake")).get("originTime")).equals("2026/11/26 08:55:00"), "a2s foreign quake keeps originTime");
        check(Json.str(a2sForeignItem.get("tsunamiText")).contains("津波の調査中"), "a2s foreign quake keeps tsunamiText");
        List<Models.Quake> a2sForeign = Quakes.parseEarthquakes(List.of(a2sForeignItem));
        check(a2sForeign.size() == 1 && a2sForeign.get(0).detail().contains("11/26 08:55"), "a2s foreign quake displays originTime as occurrence");
        check(a2sForeign.get(0).detail().contains("チリ沖の地震で津波の調査中"), "a2s foreign quake displays tsunamiText");

        // 通知の重複排除: 同じ地震が SSE と REST の両方から届いても ID が一致し、1回だけ通知される
        List<Models.NotifyEvent> a2sRestNotify = Notify.parseQuakeEvents(a2sDirectRoot, 30);
        List<Models.NotifyEvent> a2sPathNotify = Notify.parseQuakeEvents(List.of(a2sQuakeItem), 30);
        check(a2sPathNotify.size() == 1 && a2sPathNotify.get(0).id().equals("quake:tg-a2s-1"), "a2s quake notify id from telegram id");
        java.util.LinkedHashSet<String> a2sSeen = new java.util.LinkedHashSet<>();
        List<Models.NotifyEvent> a2sBothPaths = new ArrayList<>(a2sRestNotify);
        a2sBothPaths.addAll(a2sPathNotify);
        check(Notify.filterNew(a2sBothPaths, a2sSeen).size() == 1, "a2s quake not double notified across sse and rest");

        // A2S EEW イベント → 内部共通形式。同一視キーが A2S の baseKey と一致する
        String a2sEewJson = "{\"kind\":\"eew\",\"key\":\"eew-2026/11/26 08:00:00@36.3,139.0\",\"baseKey\":\"eew-2026/11/26 08:00:00@36.3,139.0\","
                + "\"source\":\"p2p\",\"sources\":[\"p2p\"],\"receivedAt\":\"2026-11-26T08:00:11.000Z\",\"updatedAt\":\"2026-11-26T08:00:11.000Z\","
                + "\"time\":\"2026/11/26 08:00:10\",\"originTime\":\"2026/11/26 08:00:00\",\"cancel\":false,\"isWarn\":true,\"serial\":3,"
                + "\"eventId\":\"20261126080000\",\"isAssumption\":false,\"hypocenter\":\"群馬県南部\",\"estScale\":45,\"estMagnitude\":6.0,"
                + "\"prefs\":[\"群馬県\"],\"lat\":36.3,\"lon\":139.0,\"depthKm\":40,"
                + "\"areaArrivals\":[{\"pref\":\"群馬県\",\"arrivalTime\":\"2026/11/26 08:01:00\"}],"
                + "\"areaScales\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]}";
        Map<String, Object> a2sEewEv = Json.obj(Json.parse(a2sEewJson));
        check(A2s.isA2sEvent(a2sEewEv), "a2s eew event valid");
        Map<String, Object> a2sEewItem = A2s.toEewItem(a2sEewEv);
        check(Eew.baseKeyOfItem(a2sEewItem).equals("eew-2026/11/26 08:00:00@36.3,139.0"), "a2s eew base key matches a2s rule");
        check(Eew.isWarn(a2sEewItem) && Eew.estScale(a2sEewItem) == 45 && Eew.serial(a2sEewItem) == 3, "a2s eew warn/scale/serial");
        Models.NotifyEvent a2sEewNotify = Notify.parseEewEvent(List.of(a2sEewItem), a2sBase + 60_000);
        check(a2sEewNotify != null && a2sEewNotify.id().equals("eew:2026/11/26 08:00:00#45"), "a2s eew notify id from origin time");
        check(a2sEewNotify != null && a2sEewNotify.title().contains("（警報）"), "a2s eew notify title shows warn");
        // 到達予想: areaArrivals が areas へ載り、既存の到達予想経路を通る
        List<Models.EewArrival> a2sArr = Eew.parseArrivals(List.of(a2sEewItem), Models.LOCATIONS, a2sBase + 60_000);
        check(a2sArr.size() == 1 && a2sArr.get(0).locName().equals("高崎") && !a2sArr.get(0).estimated()
                && a2sArr.get(0).arrivalMs() == Http.parseP2pTimeMs("2026/11/26 08:01:00"), "a2s eew arrivals via existing path");
        // v0.4.0 拡張: scaleTo (上限) と isFinal (最終報) を落とさない
        String a2sEewExtJson = a2sEewJson.replace("\"cancel\":false", "\"cancel\":false,\"isFinal\":true")
                .replace("\"areaScales\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45}]",
                        "\"areaScales\":[{\"pref\":\"群馬県\",\"name\":\"群馬県南部\",\"scale\":45,\"scaleTo\":99}]");
        Map<String, Object> a2sEewExtItem = A2s.toEewItem(Json.obj(Json.parse(a2sEewExtJson)));
        check(Json.bool(a2sEewExtItem.get("isFinal")), "a2s eew keeps isFinal");
        check(Eew.areaScaleTo(Json.obj(Json.arr(a2sEewExtItem.get("areas")).get(0))) == 99, "a2s eew keeps scaleTo");
        Models.Section a2sEewExtSec = Eew.sectionFromItem(a2sEewExtItem, a2sBase + 60_000);
        check(a2sEewExtSec.body().contains("（最終報）") && a2sEewExtSec.body().contains("群馬県南部 震度5弱程度以上"),
                "a2s eew final and open-ended range shown");
        // 地点別予想 (localForecasts 相当) は areaScales からクライアント側で導出する
        Map<String, Integer> a2sLf = Eew.localForecastScales(a2sEewItem, Models.LOCATIONS);
        check(a2sLf.getOrDefault("高崎", -1) == 45 && !a2sLf.containsKey("大宮"), "a2s eew local forecasts derived from areaScales");
        // 取消の同一視キーは -cancel を付ける
        Map<String, Object> a2sEewCancelItem = A2s.toEewItem(Json.obj(Json.parse(a2sEewJson.replace("\"cancel\":false", "\"cancel\":true"))));
        check(Eew.cancelled(a2sEewCancelItem) && Eew.keyOfItem(a2sEewCancelItem).endsWith("-cancel"), "a2s eew cancel key");

        // A2S 津波イベント → 552 相当 item。通知 ID は電文 ID 由来
        String a2sTsunamiJson = "{\"kind\":\"tsunami\",\"key\":\"552:tsu-a2s-1\",\"source\":\"p2p\",\"sources\":[\"p2p\"],"
                + "\"receivedAt\":\"2026-11-26T08:05:00.000Z\",\"updatedAt\":\"2026-11-26T08:05:00.000Z\","
                + "\"time\":\"2026/11/26 08:05:00\",\"cancelled\":false,\"grade\":\"Warning\","
                + "\"areas\":[{\"name\":\"宮城県沿岸\",\"grade\":\"Warning\",\"maxHeightLabel\":\"3m\",\"arrivalLabel\":\"08:30\",\"firstHeightCondition\":null}]}";
        Map<String, Object> a2sTsunamiEv = Json.obj(Json.parse(a2sTsunamiJson));
        check(A2s.isA2sEvent(a2sTsunamiEv), "a2s tsunami event valid");
        Map<String, Object> a2sTsunamiItem = A2s.toTsunamiItem(a2sTsunamiEv);
        check(Json.str(a2sTsunamiItem.get("id")).equals("tsu-a2s-1"), "a2s tsunami item id strips 552 prefix");
        Models.Section a2sTsuSec = Quakes.parseTsunami(List.of(a2sTsunamiItem));
        check(a2sTsuSec.level().equals("danger") && a2sTsuSec.body().contains("津波警報")
                && a2sTsuSec.body().contains("予想 3m") && a2sTsuSec.body().contains("第1波到達予想 08:30"), "a2s tsunami renders via existing path");
        // v0.4.0 拡張: maxHeightValue (予想高さの数値) を保持し、ラベルが無い場合は数値から補う
        String a2sTsuValJson = a2sTsunamiJson.replace("\"maxHeightLabel\":\"3m\"", "\"maxHeightLabel\":null,\"maxHeightValue\":3");
        Map<String, Object> a2sTsuValItem = A2s.toTsunamiItem(Json.obj(Json.parse(a2sTsuValJson)));
        Map<String, Object> a2sTsuMh = Json.obj(Json.obj(Json.arr(a2sTsuValItem.get("areas")).get(0)).get("maxHeight"));
        check(Json.dbl(a2sTsuMh.get("value"), -1) == 3.0 && Json.str(a2sTsuMh.get("description")).equals("3m"), "a2s tsunami keeps maxHeightValue and derives label");
        check(Quakes.parseTsunami(List.of(a2sTsuValItem)).body().contains("予想 3m"), "a2s tsunami value-only height shown");
        Models.NotifyEvent a2sTsuNotify = Notify.parseTsunamiEvent(List.of(a2sTsunamiItem));
        check(a2sTsuNotify != null && a2sTsuNotify.id().equals("tsunami:tsu-a2s-1"), "a2s tsunami notify id from telegram id");

        // REST 履歴参照: URL 組み立てと応答のパース (検証済みイベントだけ取り込む)
        check(A2s.eventsUrl("https://a2s.example.net/", "quake", 50).equals("https://a2s.example.net/v1/events?limit=50&kind=quake"), "a2s events url with kind");
        check(A2s.eventsUrl("https://a2s.example.net", null, 0).equals("https://a2s.example.net/v1/events?limit=50"), "a2s events url defaults");
        List<Map<String, Object>> a2sResp = A2s.parseEventsResponse(Json.parse("{\"events\":[" + a2sQuakeJson + ",{\"kind\":\"quake\",\"key\":\"bad\"}]}"));
        check(a2sResp.size() == 1 && Json.str(a2sResp.get(0).get("telegramId")).equals("tg-a2s-1"), "a2s events response keeps valid only");
        // 未設定時は REST 回収も何もしない (ストアは空のまま、例外なし)
        {
            String pu = A2s.prefUrl, pt = A2s.prefToken;
            A2s.prefUrl = ""; A2s.prefToken = "";
            A2s.clearStore();
            A2s.refreshFromEvents("quake", 50);
            check(A2s.quakeItems().isEmpty(), "a2s refresh is no-op while disabled");
            A2s.prefUrl = pu; A2s.prefToken = pt;
        }

        // ストア: 重複判定は key + updatedAt (同じ内容の再送は受理しない、更新は受理する)
        {
            A2s.EventStore store = new A2s.EventStore();
            check(store.accept(a2sQuakeEv), "a2s store accepts first quake");
            check(!store.accept(a2sQuakeEv), "a2s store rejects same key+updatedAt");
            Map<String, Object> a2sQuakeUpd = Json.obj(Json.parse(a2sQuakeJson.replace("2026-11-26T08:02:00.000Z", "2026-11-26T08:03:00.000Z")));
            check(store.accept(a2sQuakeUpd), "a2s store accepts newer updatedAt");
            check(store.quakeEvents().size() == 1, "a2s store keeps one entry per key");
            check(!store.accept(Json.obj(Json.parse("{\"kind\":\"quake\",\"key\":\"bad\"}"))), "a2s store rejects invalid event");
            check(store.accept(a2sEewEv), "a2s store accepts eew");
            check(store.latestEewEvent(a2sBase + 60_000) == a2sEewEv, "a2s store latest eew within window");
            check(store.latestEewEvent(a2sBase + 11 * 60_000L) == null, "a2s store latest eew expires after display window");
            check(store.accept(a2sTsunamiEv) && store.latestTsunamiEvent() == a2sTsunamiEv, "a2s store latest tsunami");
        }

        // 静的ファサード: 未受信 (無効時と同じ状態) では空が返り、従来動作を変えない
        A2s.clearStore();
        check(A2s.quakeItems().isEmpty() && A2s.tsunamiItems().isEmpty() && A2s.latestEewItem(a2sBase) == null, "a2s facade empty when nothing received");
        check(A2s.STORE.accept(a2sQuakeEv) && A2s.quakeItems().size() == 1, "a2s facade serves stored quake");
        check(A2s.STORE.accept(a2sEewEv) && A2s.latestEewItem(a2sBase + 60_000) != null, "a2s facade serves stored eew");
        check(A2s.STORE.accept(a2sTsunamiEv) && A2s.tsunamiItems().size() == 1, "a2s facade serves stored tsunami");
        check(A2s.warningEvents().isEmpty() && A2s.typhoonEvents().isEmpty(), "a2s facade warning/typhoon empty when nothing received");
        check(A2s.STORE.accept(wnEv) && A2s.warningEventForLocation(Models.TAKASAKI) == wnEv, "a2s facade serves stored warning for location");
        check(A2s.STORE.accept(tyEv) && A2s.typhoonEvents().size() == 1, "a2s facade serves stored typhoon");
        // A2S 未設定時は台風セクションも警報と同じく何も出さない (他機能に影響しない)
        {
            String pu = A2s.prefUrl, pt = A2s.prefToken;
            A2s.prefUrl = ""; A2s.prefToken = "";
            check(Weather.typhoonSection() == null, "typhoon section null while a2s disabled");
            A2s.prefUrl = pu; A2s.prefToken = pt;
        }
        A2s.clearStore();

        // SSE フレーム解析: event/data の組み立て、コメント行 (heartbeat) の無視、複数行 data
        {
            A2s.SseParser parser = new A2s.SseParser();
            check(parser.feed(": ping 123") == null, "a2s sse comment ignored");
            check(parser.feed("event: quake") == null, "a2s sse event line buffered");
            check(parser.feed("data: {\"a\":1}") == null, "a2s sse data line buffered");
            A2s.SseParser.Frame fr = parser.feed("");
            check(fr != null && fr.type().equals("quake") && fr.data().equals("{\"a\":1}"), "a2s sse frame assembled");
            check(parser.feed("event: ready") == null && parser.feed("data: {}") == null, "a2s sse ready buffered");
            A2s.SseParser.Frame fr2 = parser.feed("");
            check(fr2 != null && fr2.type().equals("ready"), "a2s sse ready frame");
        }

        // SSE クライアント (フェイク接続で実接続しない): 再生分は通知経路へ流さず
        // 状態へマージだけし、ready 後の新規だけを live ハンドラへ流す。
        // 同じ key + updatedAt の再送は流さない
        {
          try {
            String a2sQuake2Json = a2sQuakeJson.replace("tg-a2s-1", "tg-a2s-2")
                    .replace("551:2026/11/26 08:00:00|@36.3,139.0", "551:2026/11/26 08:10:00|@36.3,139.0")
                    .replace("\"time\":\"2026/11/26 08:00:00\"", "\"time\":\"2026/11/26 08:10:00\"")
                    .replace("\"announcedTime\":\"2026/11/26 08:02:00\"", "\"announcedTime\":\"2026/11/26 08:12:00\"");
            class FakeA2sConnector implements A2s.StreamConnector {
                int connects = 0;
                @Override public java.util.stream.Stream<String> connect(String url, String token) {
                    connects++;
                    if (connects > 1) return java.util.stream.Stream.empty();
                    return java.util.stream.Stream.of(
                            "event: quake", "data: " + a2sQuakeJson, "",
                            "event: ready", "data: {}", "",
                            ": ping 1",
                            "event: quake", "data: " + a2sQuake2Json, "",
                            "event: quake", "data: " + a2sQuake2Json, "");
                }
            }
            FakeA2sConnector fakeA2s = new FakeA2sConnector();
            A2s.EventStore clientStore = new A2s.EventStore();
            List<Map<String, Object>> liveReceived = java.util.Collections.synchronizedList(new ArrayList<>());
            A2s.StreamClient client = new A2s.StreamClient(fakeA2s, "http://a2s.test", "tok", clientStore, liveReceived::add, new long[]{1, 1, 1});
            client.start();
            boolean gotLive = false;
            for (int i = 0; i < 200 && !gotLive; i++) { Thread.sleep(10); gotLive = liveReceived.size() >= 1; }
            check(gotLive, "a2s sse live event delivered after ready");
            Thread.sleep(100); // 再生分の誤配送や重複の再送が無いことを少し待って確認する
            check(liveReceived.size() == 1 && Json.str(liveReceived.get(0).get("telegramId")).equals("tg-a2s-2"), "a2s sse replay not delivered, duplicate not delivered");
            check(clientStore.quakeEvents().size() == 2, "a2s sse replay merged into store");
            client.stop();
            check(client.isStopped(), "a2s sse stopped");
            Thread.sleep(100);
            int connectsAfterStop = fakeA2s.connects;
            Thread.sleep(100);
            check(fakeA2s.connects == connectsAfterStop, "a2s sse no reconnect after stop");
          } catch (Exception e) { check(false, "a2s sse block threw: " + e); }
        }

        System.out.println("ALL TESTS PASSED (" + passed + ")");
    }
}
