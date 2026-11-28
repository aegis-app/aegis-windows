package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// ========================================
// 🌏 Quakes (地震・津波: A.A.S 経由の 551 / 552 相当)
// ========================================
// 📌 取得は A.A.S のみ (完全一本化)。ここでのパース・マージは
// A.A.S 変換済みの item (551 / 552 相当形式) に対する処理。
// 📝 地震の統合規則: 気象庁は1つの地震を段階的に発表する (震度
// 速報→震源情報→詳細情報) ため、履歴には同一地震の電文が複数
// 並ぶ。発生時刻＋震源名でグルーピングし、最も完全な報を主として
// 1件に統合する。
public final class Quakes {
    private Quakes() {}

    // ----------------------------------------
    // 🏷️ 震度・重要度の表記
    // ----------------------------------------
    public static String scaleToStr(int scale) {
        if (scale <= 0) return "不明"; // -1 は「震度不明」
        return switch (scale) {
            case 10 -> "1";
            case 20 -> "2";
            case 30 -> "3";
            case 40 -> "4";
            case 45 -> "5弱";
            case 50 -> "5強";
            case 55 -> "6弱";
            case 60 -> "6強";
            case 70 -> "7";
            default -> String.valueOf(scale);
        };
    }

    public static String severityEmoji(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> "🔴";
            case "HIGH" -> "🟠";
            case "MEDIUM" -> "🟡";
            case "LOW" -> "🟢";
            default -> "ℹ️";
        };
    }

    static String severityOf(int scale, double magnitude) {
        if (scale >= 45 || magnitude >= 6.0) return "CRITICAL";
        if (scale >= 40 || magnitude >= 5.0) return "HIGH";
        if (scale >= 30 || magnitude >= 4.5) return "MEDIUM";
        if (scale >= 20) return "LOW";
        return "INFO";
    }

    // ----------------------------------------
    // 📝 電文の種別・ラベル
    // ----------------------------------------
    // 📝 報種別の完全さの順位 (詳細情報 > 震源・震度情報 > 震源情報 > 震度速報)
    static int typeRank(String issueType) {
        return switch (issueType == null ? "" : issueType) {
            case "DetailScale" -> 4;
            case "ScaleAndDestination", "Foreign" -> 3;
            case "Destination" -> 2;
            case "ScalePrompt" -> 1;
            default -> 0;
        };
    }

    // 🖥️ issue (報種別・訂正情報) から表示用ラベルを作る。訂正報が最優先
    static String issueLabel(Map<String, Object> item) {
        Map<String, Object> issue = Json.obj(item.get("issue"));
        String correct = Json.str(issue.get("correct"));
        if (!correct.isEmpty() && !"None".equals(correct)) return "訂正報";
        return switch (Json.str(issue.get("type"))) {
            case "ScalePrompt" -> "速報";
            case "DetailScale" -> "確定報";
            case "Destination", "ScaleAndDestination", "Foreign" -> "続報";
            default -> "";
        };
    }

    static String normalizeHypoName(String name) {
        return name == null ? "" : name.replace("　", "").replace(" ", "").trim();
    }

    // 🌏 震央座標が有効か (未確定時に範囲外の番兵値が入ることがある)
    static boolean validCoord(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0);
    }

    // ----------------------------------------
    // 📍 観測点情報 (DetailScale の points)
    // ----------------------------------------
    // 観測点として扱うのは詳細情報 (DetailScale) の points のみ。
    // 震度速報 (ScalePrompt) の points は地域単位の震度で観測点
    // データではないため、速報のみの地震では「観測点情報なし」と
    // する。issue 種別が無いデータ (旧形式) は詳細情報とみなす
    static boolean isDetailLike(Map<String, Object> item) {
        String type = Json.str(Json.obj(item.get("issue")).get("type"));
        return type.isEmpty() || "DetailScale".equals(type);
    }

    // 🌏 DetailScale の points の和集合 (同一観測点は最大震度を残す)。
    // 地域代表値 (isArea=true) は観測点の実測ではないため除外する
    static List<Map<String, Object>> detailPointsOf(List<Map<String, Object>> sortedItems) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> item : sortedItems) {
            if (!isDetailLike(item)) continue;
            for (Object pointObj : Json.arr(item.get("points"))) {
                Map<String, Object> point = Json.obj(pointObj);
                if (Json.bool(point.get("isArea"))) continue;
                String key = Json.str(point.get("pref")) + "|" + Json.str(point.get("addr"));
                Map<String, Object> previous = byKey.get(key);
                if (previous == null || Json.dbl(point.get("scale"), 0) > Json.dbl(previous.get("scale"), 0)) {
                    byKey.put(key, point);
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    static String pointName(Map<String, Object> point) {
        String addr = Json.str(point.get("addr"));
        return addr.isEmpty() ? Json.str(point.get("pref"), "地点名不明") : addr;
    }

    // 🌏 主要観測点を「地点名: 実測震度」の形で震度降順に最大 limit 件返す
    public static List<String> topObservationPoints(List<Map<String, Object>> points, int limit) {
        List<Map<String, Object>> sorted = new ArrayList<>(points);
        sorted.sort((a, b) -> {
            int byScale = Integer.compare((int) Json.dbl(b.get("scale"), 0), (int) Json.dbl(a.get("scale"), 0));
            return byScale != 0 ? byScale : pointName(a).compareTo(pointName(b));
        });
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> point : sorted) {
            if (lines.size() >= limit) break;
            int scale = (int) Json.dbl(point.get("scale"), 0);
            if (scale <= 0) continue;
            lines.add(pointName(point) + ": 実測震度 " + scaleToStr(scale));
        }
        return lines;
    }

    // 🖥️ 観測点情報の表示文。DetailScale の points が無ければ「観測点情報なし」
    public static String observationPointsNote(List<Map<String, Object>> detailPoints, int maxScale) {
        if (detailPoints == null || detailPoints.isEmpty()) {
            return "観測点情報なし（詳細情報の観測点データがない報種別のため）";
        }
        StringBuilder sb = new StringBuilder("観測点情報（実測）: 全").append(detailPoints.size()).append("地点");
        // 🌏 最大震度を観測した地点名とその実測震度
        String topName = "";
        int topScale = 0;
        for (Map<String, Object> point : detailPoints) {
            int scale = (int) Json.dbl(point.get("scale"), 0);
            if (scale > topScale) {
                topScale = scale;
                topName = pointName(point);
            }
        }
        if (!topName.isEmpty()) {
            sb.append(" / 最大震度観測地点 ").append(topName)
                    .append(": 実測震度 ").append(scaleToStr(maxScale > 0 ? maxScale : topScale));
        }
        List<String> tops = topObservationPoints(detailPoints, 5);
        if (!tops.isEmpty()) sb.append(" / 主要観測点（実測）: ").append(String.join("、", tops));
        return sb.toString();
    }

    // ----------------------------------------
    // 🌏 地震のパースと統合
    // ----------------------------------------
    public static List<Models.Quake> parseEarthquakes(Object root) {
        return parseEarthquakes(root, Geo.effectiveLocations());
    }

    public static List<Models.Quake> parseEarthquakes(Object root, Models.Location[] locations) {
        // ----------------------------------------
        // 🌏 グルーピング (発生時刻＋震源名、座標近接を補助キー)
        // ----------------------------------------
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<List<Map<String, Object>>> unnamedGroups = new ArrayList<>(); // 震源名なし (震度速報)
        for (Object itemObj : Json.arr(root)) {
            Map<String, Object> item = Json.obj(itemObj);
            Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
            if (earthquake.isEmpty()) continue;
            Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
            String time = Json.str(earthquake.get("time"));
            String name = normalizeHypoName(Json.str(hypocenter.get("name")));
            double lat = Json.dbl(hypocenter.get("latitude"), 0);
            double lon = Json.dbl(hypocenter.get("longitude"), 0);
            if (name.isEmpty()) {
                List<Map<String, Object>> group = null;
                for (List<Map<String, Object>> candidate : unnamedGroups) {
                    if (time.equals(Json.str(Json.obj(candidate.get(0).get("earthquake")).get("time")))) {
                        group = candidate;
                        break;
                    }
                }
                if (group == null) {
                    group = new ArrayList<>();
                    unnamedGroups.add(group);
                }
                group.add(item);
                continue;
            }
            List<Map<String, Object>> group = null;
            for (List<Map<String, Object>> candidate : groups) {
                Map<String, Object> candidateEq = Json.obj(candidate.get(0).get("earthquake"));
                if (!time.equals(Json.str(candidateEq.get("time")))) continue;
                Map<String, Object> candidateHypo = Json.obj(candidateEq.get("hypocenter"));
                String candidateName = normalizeHypoName(Json.str(candidateHypo.get("name")));
                double candidateLat = Json.dbl(candidateHypo.get("latitude"), 0);
                double candidateLon = Json.dbl(candidateHypo.get("longitude"), 0);
                // 📝 震源名の表記揺れ対策: 発生時刻が同じで震央座標が
                // 近い (50km 以内) 電文も同一地震として束ねる
                if (name.equals(candidateName)
                        || (validCoord(lat, lon) && validCoord(candidateLat, candidateLon)
                        && Geo.haversineKm(lat, lon, candidateLat, candidateLon) <= 50.0)) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                group = new ArrayList<>();
                groups.add(group);
            }
            group.add(item);
        }
        // 🌏 震源名なしの電文は、同じ発生時刻の震源名ありグループが
        // 1つだけならそこへ寄せる
        for (List<Map<String, Object>> unnamed : unnamedGroups) {
            String time = Json.str(Json.obj(unnamed.get(0).get("earthquake")).get("time"));
            List<Map<String, Object>> named = null;
            int namedCount = 0;
            for (List<Map<String, Object>> candidate : groups) {
                if (time.equals(Json.str(Json.obj(candidate.get(0).get("earthquake")).get("time")))) {
                    named = candidate;
                    namedCount++;
                }
            }
            if (namedCount == 1 && named != null) named.addAll(unnamed);
            else groups.add(unnamed);
        }
        List<Models.Quake> quakes = new ArrayList<>();
        for (List<Map<String, Object>> group : groups) quakes.add(mergeGroup(group, locations));
        return quakes;
    }

    // 🌏 同一地震の電文群を1件に統合する。主報 (種別優先度→発表時刻
    // の順で最上位) の値を主にし、番兵値などで欠けた項目は他の報
    // から補う。観測点は全報の和集合 (同一観測点は最大の震度) を使う
    private static Models.Quake mergeGroup(List<Map<String, Object>> items, Models.Location[] locations) {
        List<Map<String, Object>> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> {
            int rankA = typeRank(Json.str(Json.obj(a.get("issue")).get("type")));
            int rankB = typeRank(Json.str(Json.obj(b.get("issue")).get("type")));
            if (rankA != rankB) return Integer.compare(rankB, rankA);
            long timeA = Http.parseP2pTimeMs(Json.str(Json.obj(a.get("issue")).get("time"), Json.str(a.get("time"))));
            long timeB = Http.parseP2pTimeMs(Json.str(Json.obj(b.get("issue")).get("time"), Json.str(b.get("time"))));
            return Long.compare(timeB, timeA);
        });
        Map<String, Object> primary = sorted.get(0);
        Map<String, Object> earthquake = Json.obj(primary.get("earthquake"));
        Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
        String time = Json.str(earthquake.get("time"));
        // 🕐 国外地震 (Foreign) は発生時刻の原表記 (originTime) が
        // あればそちらを発生時刻として使う (time は発表時刻側に倒れるため)
        {
            String originTime = Json.str(earthquake.get("originTime"));
            String primaryType = Json.str(Json.obj(primary.get("issue")).get("type"));
            if (!originTime.isEmpty() && "Foreign".equals(primaryType) && Http.parseP2pTimeMs(originTime) > 0) {
                time = originTime;
            }
        }
        // 📝 欠けた項目の補完 (主報→他報の順に、番兵値でない最初の値を採る)
        int maxScale = (int) Json.dbl(earthquake.get("maxScale"), -1);
        double magnitude = Json.dbl(hypocenter.get("magnitude"), -1);
        int depth = (int) Json.dbl(hypocenter.get("depth"), -999);
        String hypoName = Json.str(hypocenter.get("name"));
        double lat = Json.dbl(hypocenter.get("latitude"), 0);
        double lon = Json.dbl(hypocenter.get("longitude"), 0);
        String tsunami = Json.str(earthquake.get("domesticTsunami"), "Unknown");
        for (int i = 1; i < sorted.size(); i++) {
            Map<String, Object> otherEq = Json.obj(sorted.get(i).get("earthquake"));
            Map<String, Object> otherHypo = Json.obj(otherEq.get("hypocenter"));
            if (maxScale < 0) maxScale = (int) Json.dbl(otherEq.get("maxScale"), -1);
            if (magnitude < 0) magnitude = Json.dbl(otherHypo.get("magnitude"), -1);
            // 📝 深さは「判明している報」を優先する: 判明 = 0 以上
            // (0 は「ごく浅い」)。不明センチネル (負値) の報で塞がず、
            // 判明値を持つ後続の報まで探す
            if (depth < 0) {
                int otherDepth = (int) Json.dbl(otherHypo.get("depth"), -999);
                if (otherDepth >= 0) depth = otherDepth;
            }
            if (hypoName.isEmpty()) hypoName = Json.str(otherHypo.get("name"));
            if (!validCoord(lat, lon)
                    && validCoord(Json.dbl(otherHypo.get("latitude"), 0), Json.dbl(otherHypo.get("longitude"), 0))) {
                lat = Json.dbl(otherHypo.get("latitude"), 0);
                lon = Json.dbl(otherHypo.get("longitude"), 0);
            }
            // 🌊 津波は「確認中」しかなければそのまま、確定した値が
            // 他報にあればそちらを優先する
            if (("Unknown".equals(tsunami) || "Checking".equals(tsunami))
                    && !"Unknown".equals(Json.str(otherEq.get("domesticTsunami"), "Unknown"))
                    && !"Checking".equals(Json.str(otherEq.get("domesticTsunami"), "Unknown"))) {
                tsunami = Json.str(otherEq.get("domesticTsunami"));
            }
        }
        // 🌏 観測点・地域点の和集合 (主報の並びを先頭にし、同一地点は
        // 最大震度を残す)。地域点 (isArea) と実測点は同名でも別
        // エントリとして扱う
        Map<String, Map<String, Object>> pointByKey = new LinkedHashMap<>();
        for (Map<String, Object> item : sorted) {
            for (Object pointObj : Json.arr(item.get("points"))) {
                Map<String, Object> point = Json.obj(pointObj);
                String key = Json.str(point.get("pref")) + "|" + Json.str(point.get("addr")) + "|" + Json.bool(point.get("isArea"));
                Map<String, Object> previous = pointByKey.get(key);
                if (previous == null || Json.dbl(point.get("scale"), 0) > Json.dbl(previous.get("scale"), 0)) {
                    pointByKey.put(key, point);
                }
            }
        }
        List<Map<String, Object>> points = new ArrayList<>(pointByKey.values());

        String shortTime = time.length() >= 16 ? time.substring(5, 10) + " " + time.substring(11, 16) : time;
        // 🌦️ 深さの意味: 0 = 気象庁の「ごく浅い」(実データで確認済み)。
        // 負値は震源未確定のセンチネル (震度速報は緯度経度 -200・
        // マグニチュード -1 とともに depth=-1 を返す) で「不明」。
        // 欠落・null も不明。0 を「深さ約0km」と表示してはならない
        String magnitudeText = magnitude < 0 ? "M不明" : "M" + magnitude;
        String depthText = depth == 0 ? "深さごく浅い" : (depth < 0 ? "深さ不明" : "深さ約" + depth + "km");
        StringBuilder detail = new StringBuilder();
        detail.append(shortTime).append("発生 / ").append(magnitudeText).append(" / ").append(depthText);
        // 🌊 津波区分は安全側に判定する。「心配なし」と断定してよい
        // のは明示値 None のみで、Unknown・Checking・未知の値は
        // すべて「確認中」に倒す
        switch (tsunami) {
            case "None" -> detail.append(" / 津波の心配なし");
            case "Forecast" -> detail.append(" / 若干の海面変動の可能性あり");
            case "Advisory" -> detail.append(" / 津波注意報レベル");
            case "Warning" -> detail.append(" / 津波警報レベル");
            default -> detail.append(" / 津波の有無は確認中");
        }
        String label = issueLabel(primary);
        if (!label.isEmpty()) detail.append(" / 報種別： ").append(label);
        // 🌊 電文コメントの原文 (A.A.S の tsunamiText、国外地震等)。あれば落とさず表示する
        for (Map<String, Object> item : sorted) {
            String text = Json.str(item.get("tsunamiText"));
            if (!text.isEmpty()) {
                detail.append(" / ").append(text.trim());
                break;
            }
        }
        // 🌏 速報段階 (ScalePrompt が主報) の震源・マグニチュード・
        // 最大震度は推定値であり、確定値と混同しないよう明示する。
        // 確定報が主報になればこの注記は付かない
        String primaryType = Json.str(Json.obj(primary.get("issue")).get("type"));
        if ("ScalePrompt".equals(primaryType)) {
            detail.append(" / 推定情報（速報段階）: 震源・マグニチュード・最大震度は推定値です（確定報で更新されます）");
        }
        // 🌏 最大震度地点: 先頭一致ではなく、登録地点の市区町村と
        // 一致する観測点を優先する。県内最大と市区町村照合の地点
        // 震度もここで組み立てる
        Map<String, Integer> prefMax = new LinkedHashMap<>();
        LinkedHashSet<String> locationPrefs = new LinkedHashSet<>();
        for (Models.Location loc : locations) {
            String pref = Geo.prefName(loc.prefCode());
            if (!pref.isEmpty()) locationPrefs.add(pref);
        }
        String topAddr = "";
        String topAddrLocal = "";
        int topCount = 0;
        for (Map<String, Object> point : points) {
            int scale = (int) Json.dbl(point.get("scale"), 0);
            if (scale == maxScale && maxScale > 0) {
                topCount++;
                String addr = Json.str(point.get("addr"), Json.str(point.get("pref")));
                if (topAddr.isEmpty()) topAddr = addr;
                if (topAddrLocal.isEmpty()) {
                    for (Models.Location loc : locations) {
                        if (Geo.addrMatchesCity(Json.str(point.get("addr")), loc)) {
                            topAddrLocal = addr;
                            break;
                        }
                    }
                }
            }
            String pref = Json.str(point.get("pref"));
            if (locationPrefs.contains(pref)) prefMax.merge(pref, scale, Math::max);
        }
        // 📍 都道府県別の最大震度 (prefScales) からも県内最大を導出
        // する。震度速報段階 (観測点なし・地域点のみ) でも、地域点が
        // 欠ける場合でも県別最大震度が出るようにするための取り込み
        for (Map<String, Object> item : sorted) {
            for (Object prefObj : Json.arr(item.get("prefScales"))) {
                Map<String, Object> prefScale = Json.obj(prefObj);
                String pref = Json.str(prefScale.get("pref"));
                int scale = (int) Json.dbl(prefScale.get("scale"), 0);
                if (scale > 0 && locationPrefs.contains(pref)) prefMax.merge(pref, scale, Math::max);
            }
        }
        String topShown = !topAddrLocal.isEmpty() ? topAddrLocal : topAddr;
        if (!topShown.isEmpty()) {
            detail.append(" / 最大震度観測: ").append(topShown);
            if (topCount > 1) detail.append(" ほか").append(topCount - 1).append("地点");
        }
        // 📍 観測点情報と実測値 (DetailScale の points のみを観測点として扱う)
        detail.append(" / ").append(observationPointsNote(detailPointsOf(sorted), maxScale));
        // 🌏 市区町村が一致する観測点の震度を「○○市の震度」として
        // 優先表示する。これはその市区町村の観測点の実測値そのもの
        // なので「実測」と明示する
        Map<String, Integer> cityScaleByLocation = new LinkedHashMap<>();
        for (Models.Location loc : locations) {
            String pref = Geo.prefName(loc.prefCode());
            int cityScale = -1;
            for (Map<String, Object> point : points) {
                if (!pref.equals(Json.str(point.get("pref")))) continue;
                if (Geo.addrMatchesCity(Json.str(point.get("addr")), loc)) {
                    cityScale = Math.max(cityScale, (int) Json.dbl(point.get("scale"), 0));
                }
            }
            if (cityScale > 0) {
                cityScaleByLocation.put(loc.name(), cityScale);
                detail.append(" / ").append(Geo.cityOf(loc)).append("の震度 ")
                        .append(scaleToStr(cityScale)).append("（実測）");
            }
        }
        if (!prefMax.isEmpty()) {
            // 🌏 「高崎の震度」等の地点震度と誤読されないよう、県内
            // 最大震度であることを明記する。県内最大も観測点の実測
            // 値の最大であるため「実測」と明示する
            for (Map.Entry<String, Integer> entry : prefMax.entrySet()) {
                detail.append(" / ").append(entry.getKey()).append("内の最大震度 ")
                        .append(scaleToStr(entry.getValue())).append("（実測）");
            }
        }
        // 🌏 最寄り震度は廃止: 市区町村の実測が無い地点は、最寄り
        // 観測点の実測に基づく推定を表示せず、震度を表示しない
        // 「実測なし」と明示する (推定値を新たに作らない)
        for (Models.Location loc : locations) {
            if (cityScaleByLocation.containsKey(loc.name())) continue; // 実測を表示済み
            if (!points.isEmpty()) {
                detail.append(" / ").append(loc.name()).append("の震度: 実測なし");
            }
        }
        return new Models.Quake(hypoName.isEmpty() ? "不明" : hypoName, detail.toString(),
                severityOf(maxScale, magnitude), maxScale, lat, lon, Http.parseP2pTimeMs(time));
    }

    // 🌏 地震一覧: A.A.S のみから取る (完全一本化)。履歴は REST
    // (GET /v1/events) で回収してストアへ取り込んでから変換する。
    // A.A.S 未設定・到達不能時は空の一覧を返す (地震情報は出ないが、
    // 呼び出し側や他機能は落とさない)
    public static List<Models.Quake> quakeItems(int limit) throws Exception {
        A2s.refreshFromEvents("quake", Math.max(limit, 50));
        List<Models.Quake> quakes = parseEarthquakes(A2s.quakeItems());
        return quakes.size() > limit ? new ArrayList<>(quakes.subList(0, limit)) : quakes;
    }

    // ----------------------------------------
    // 🌊 津波
    // ----------------------------------------
    public static Models.Section parseTsunami(Object root) {
        List<Object> data = Json.arr(root);
        if (data.isEmpty()) {
            return new Models.Section("🌊 津波情報",
                    "現在、発表されている津波警報・注意報はありません。\n強い地震を感じたら、すぐ高台へ避難してください。", "ok");
        }
        Map<String, Object> item = Json.obj(data.get(0));
        boolean cancelled = Json.bool(item.get("cancelled"));
        StringBuilder names = new StringBuilder();
        // 📝 警戒レベルは「全エリア中の最大 grade」で判定する
        // (先頭エリアだけでは過小評価になる)
        String grade = "";
        int bestRank = -1;
        for (Object areaObj : Json.arr(item.get("areas"))) {
            Map<String, Object> area = Json.obj(areaObj);
            String areaGrade = Json.str(area.get("grade"));
            int rank = "MajorWarning".equals(areaGrade) ? 3 : "Warning".equals(areaGrade) ? 2 : "Watch".equals(areaGrade) ? 1 : 0;
            if (rank > bestRank) {
                bestRank = rank;
                grade = areaGrade;
            }
            if (names.length() > 0) names.append("、");
            names.append(Json.str(area.get("name")));
            Map<String, Object> maxHeight = Json.obj(area.get("maxHeight"));
            String heightDesc = Json.str(maxHeight.get("description"));
            if (!heightDesc.isEmpty()) names.append("（予想 ").append(heightDesc).append("）");
            Map<String, Object> firstHeight = Json.obj(area.get("firstHeight"));
            String arrival = Json.str(firstHeight.get("arrivalTime"));
            if (!arrival.isEmpty()) {
                names.append("（第1波到達予想 ").append(arrival.length() >= 16 ? arrival.substring(11, 16) : arrival).append("）");
            }
        }
        String body;
        String level = "warn";
        if (cancelled) {
            body = "津波警報・注意報は解除されました。対象: " + names;
            level = "ok";
        } else if ("MajorWarning".equals(grade)) {
            body = "🔴 大津波警報: " + names + "\nただちに高台・津波避難ビルへ避難してください。";
            level = "danger";
        } else if ("Warning".equals(grade)) {
            body = "🔴 津波警報: " + names + "\nただちに高台・津波避難ビルへ避難してください。";
            level = "danger";
        } else if ("Watch".equals(grade)) {
            body = "🟠 津波注意報: " + names + "\n海から上がり、海岸から離れてください。";
            level = "warn";
        } else {
            body = "津波予報/情報: " + names;
        }
        return new Models.Section("🌊 津波情報", body + "\n発表: " + Json.str(item.get("time")), level);
    }

    // 🌊 津波セクション: A.A.S のみから取る (完全一本化)。履歴は
    // REST (GET /v1/events) で回収し、最新の1件で判定する
    // (parseTsunami は先頭の item を使う)。A.A.S 未設定・未受信時は
    // 「発表なし」のセクションになる
    public static Models.Section tsunamiSection() throws Exception {
        A2s.refreshFromEvents("tsunami", 5);
        List<Object> items = new ArrayList<>(A2s.tsunamiItems());
        items.sort((a, b) -> Long.compare(
                Http.parseP2pTimeMs(Json.str(Json.obj(b).get("time"))),
                Http.parseP2pTimeMs(Json.str(Json.obj(a).get("time")))));
        return parseTsunami(items);
    }
}
