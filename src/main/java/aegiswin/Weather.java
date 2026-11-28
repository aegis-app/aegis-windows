package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

// ========================================
// 🌦️ Weather (気象: 気象庁予報 / Open-Meteo / A.A.S 経由の警報・台風)
// ========================================
// 📌 警報・注意報と台風は A.A.S 経由に一本化し、気象庁の警報
// JSON・台風 targetTc の直読みはしない。天気予報そのもの (気象庁
// 予報 JSON・Open-Meteo) は A.A.S 対象外のため直接取得する。
public final class Weather {
    private Weather() {}

    // 🌦️ WMO 天気コード → 天気文
    static final Map<String, String> WMO = Map.ofEntries(
            Map.entry("0", "快晴"), Map.entry("1", "晴れ"), Map.entry("2", "一部曇り"), Map.entry("3", "曇り"),
            Map.entry("45", "霧"), Map.entry("48", "霧"), Map.entry("51", "霧雨"), Map.entry("53", "霧雨"), Map.entry("55", "霧雨"),
            Map.entry("61", "雨"), Map.entry("63", "雨"), Map.entry("65", "大雨"), Map.entry("71", "雪"), Map.entry("73", "雪"),
            Map.entry("75", "大雪"), Map.entry("80", "にわか雨"), Map.entry("81", "にわか雨"), Map.entry("82", "激しい雨"),
            Map.entry("95", "雷雨"), Map.entry("56", "着氷性の霧雨"), Map.entry("57", "着氷性の霧雨"),
            Map.entry("66", "着氷性の雨"), Map.entry("67", "着氷性の雨"), Map.entry("77", "雪あられ"),
            Map.entry("85", "雪"), Map.entry("86", "雪"), Map.entry("96", "雹を伴う雷雨"), Map.entry("99", "雹を伴う雷雨"));

    // ----------------------------------------
    // 🌦️ 気象庁予報 JSON のパース
    // ----------------------------------------
    // 🌦️ 予報JSONから対象エリアの天気文を取り出す (配列の先頭から今日・明日・明後日)
    public static String[] parseForecast(Object root, String areaName) {
        List<Object> arr = Json.arr(root);
        if (arr.isEmpty()) return new String[0];
        Map<String, Object> first = Json.obj(arr.get(0));
        List<Object> timeSeries = Json.arr(first.get("timeSeries"));
        if (timeSeries.isEmpty()) return new String[0];
        List<Object> areas = Json.arr(Json.obj(timeSeries.get(0)).get("areas"));
        Map<String, Object> chosen = null;
        for (Object areaObj : areas) {
            Map<String, Object> area = Json.obj(areaObj);
            if (areaName.equals(Json.str(Json.obj(area.get("area")).get("name")))) {
                chosen = area;
                break;
            }
        }
        if (chosen == null && !areas.isEmpty()) chosen = Json.obj(areas.get(0));
        if (chosen == null) return new String[0];
        List<Object> weathers = Json.arr(chosen.get("weathers"));
        String[] texts = new String[weathers.size()];
        for (int i = 0; i < weathers.size(); i++) texts[i] = cleanWeather(Json.str(weathers.get(i)));
        return texts;
    }

    // ⚠️ 予報JSONから対象エリアの一次細分区域コードを取り出す
    public static String forecastAreaCode(Object root, String areaName) {
        List<Object> arr = Json.arr(root);
        if (arr.isEmpty()) return "";
        List<Object> timeSeries = Json.arr(Json.obj(arr.get(0)).get("timeSeries"));
        if (timeSeries.isEmpty()) return "";
        for (Object areaObj : Json.arr(Json.obj(timeSeries.get(0)).get("areas"))) {
            Map<String, Object> area = Json.obj(areaObj);
            Map<String, Object> areaInfo = Json.obj(area.get("area"));
            if (areaName.equals(Json.str(areaInfo.get("name")))) return Json.str(areaInfo.get("code"));
        }
        return "";
    }

    // 📍 予報JSONに含まれる一次細分区域名の一覧 (地点追加フォームの選択候補用)
    public static List<String> forecastAreaNames(Object root) {
        List<String> names = new ArrayList<>();
        List<Object> arr = Json.arr(root);
        if (arr.isEmpty()) return names;
        List<Object> timeSeries = Json.arr(Json.obj(arr.get(0)).get("timeSeries"));
        if (timeSeries.isEmpty()) return names;
        for (Object areaObj : Json.arr(Json.obj(timeSeries.get(0)).get("areas"))) {
            String name = Json.str(Json.obj(Json.obj(areaObj).get("area")).get("name"));
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    // 🌧️ 気象庁予報の降水確率 (6時間ごと) を整形する。対象データが無ければ ""。
    // 経過済みの時間帯 (枠の終了時刻が現在時刻以前) は表示しない
    public static String parsePops(Object root, String areaCode) {
        return parsePops(root, areaCode, System.currentTimeMillis());
    }

    public static String parsePops(Object root, String areaCode, long nowMs) {
        List<Object> arr = Json.arr(root);
        if (arr.isEmpty()) return "";
        for (Object seriesObj : Json.arr(Json.obj(arr.get(0)).get("timeSeries"))) {
            Map<String, Object> series = Json.obj(seriesObj);
            List<Object> seriesAreas = Json.arr(series.get("areas"));
            if (seriesAreas.isEmpty() || !Json.obj(seriesAreas.get(0)).containsKey("pops")) continue;
            Map<String, Object> chosen = null;
            for (Object areaObj : seriesAreas) {
                Map<String, Object> area = Json.obj(areaObj);
                if (areaCode.equals(Json.str(Json.obj(area.get("area")).get("code")))) {
                    chosen = area;
                    break;
                }
            }
            if (chosen == null) chosen = Json.obj(seriesAreas.get(0));
            List<Object> pops = Json.arr(chosen.get("pops"));
            List<Object> defs = Json.arr(series.get("timeDefines"));
            StringBuilder sb = new StringBuilder("降水確率： ");
            int shown = 0;
            String prevDate = "";
            for (int i = 0; i < pops.size() && shown < 6; i++) {
                String def = Json.str(defs.size() > i ? defs.get(i) : null);
                if (def.length() < 13) continue;
                // 🕐 枠 (6時間) の終了時刻を過ぎた時間帯は経過済みとして除く
                long slotStartMs = Http.parseJmaTimeMs(def);
                if (slotStartMs > 0 && slotStartMs + 6 * 3600_000L <= nowMs) continue;
                int hour = Integer.parseInt(def.substring(11, 13));
                String date = def.substring(5, 10).replace('-', '/');
                if (shown > 0) sb.append(" / ");
                if (!date.equals(prevDate)) {
                    sb.append(date).append(" ");
                    prevDate = date;
                }
                sb.append(hour).append("〜").append((hour + 6) % 24).append("時 ").append(Json.str(pops.get(i))).append("%");
                shown++;
            }
            return shown > 0 ? sb.toString() : "";
        }
        return "";
    }

    // 🌦️ 気象庁予報の気温 (temps は [今日の最低, 今日の最高, 明日の最低, 明日の最高])。無ければ ""
    // 後方互換用: 区域コードなし (先頭エリア採用)
    public static String parseTemps(Object root) {
        return parseTemps(root, "");
    }

    // 🌧️ 気温も降水確率と同じく一次細分区域コードで照合して選ぶ。
    // ただし temps 系列のエリアは区域の代表地点 (前橋・熊谷など) で、
    // コードが区域コードと一致しない配信形式もあるため、(1) コード
    // 完全一致 → (2) 天気系列における区域の並び順に対応する代表地点
    // → (3) 先頭エリア、の順で選ぶ
    public static String parseTemps(Object root, String areaCode) {
        List<Object> arr = Json.arr(root);
        if (arr.isEmpty()) return "";
        List<Object> series = Json.arr(Json.obj(arr.get(0)).get("timeSeries"));
        for (Object seriesObj : series) {
            Map<String, Object> timeSeries = Json.obj(seriesObj);
            List<Object> seriesAreas = Json.arr(timeSeries.get("areas"));
            if (seriesAreas.isEmpty() || !Json.obj(seriesAreas.get(0)).containsKey("temps")) continue;
            Map<String, Object> chosen = null;
            if (areaCode != null && !areaCode.isEmpty()) {
                // 📝 (1) 区域コード完全一致
                for (Object areaObj : seriesAreas) {
                    Map<String, Object> area = Json.obj(areaObj);
                    if (areaCode.equals(Json.str(Json.obj(area.get("area")).get("code")))) {
                        chosen = area;
                        break;
                    }
                }
                // 🌦️ (2) 天気系列での区域の並び順に対応する代表地点
                if (chosen == null && !series.isEmpty()) {
                    List<Object> weatherAreas = Json.arr(Json.obj(series.get(0)).get("areas"));
                    for (int i = 0; i < weatherAreas.size(); i++) {
                        String code = Json.str(Json.obj(Json.obj(weatherAreas.get(i)).get("area")).get("code"));
                        if (areaCode.equals(code) && i < seriesAreas.size()) {
                            chosen = Json.obj(seriesAreas.get(i));
                            break;
                        }
                    }
                }
            }
            if (chosen == null) chosen = Json.obj(seriesAreas.get(0));
            List<Object> temps = Json.arr(chosen.get("temps"));
            if (temps.size() < 4) return "";
            String city = Json.str(Json.obj(chosen.get("area")).get("name"));
            return "気温（" + city + "）： 今日 " + Json.str(temps.get(0)) + "〜" + Json.str(temps.get(1))
                    + "℃ / 明日 " + Json.str(temps.get(2)) + "〜" + Json.str(temps.get(3)) + "℃";
        }
        return "";
    }

    // 🌦️ 気象庁の天気文は全角スペース区切りで間延びして見えるため整形する
    static String cleanWeather(String text) {
        return text.replace('　', ' ').replaceAll(" +", " ").trim();
    }

    // ----------------------------------------
    // ⚠️ 警報・注意報 (A.A.S 経由)
    // ----------------------------------------
    // ⚠️ A.A.S の警報イベント (一次細分区域ごとに1件) から、発表中の
    // 警報・注意報を (コード, 名称, 重要度) で取り出す。A.A.S 側で
    // 名称・重要度 (特別警報=4 / 警報=3 / 注意報=2) まで正規化済みの
    // ため、クライアント側のコード表は持たない。解除イベント
    // (released: true) は発表中ではないため空を返す
    public static List<Models.WarningEntry> warningEntriesFromA2s(Map<String, Object> event) {
        List<Models.WarningEntry> entries = new ArrayList<>();
        if (event == null || Json.bool(event.get("released"))) return entries;
        for (Object warningObj : Json.arr(event.get("warnings"))) {
            Map<String, Object> warning = Json.obj(warningObj);
            String code = Json.str(warning.get("code"));
            String name = Json.str(warning.get("name"));
            if (code.isEmpty() || name.isEmpty()) continue;
            int severity = (int) Json.dbl(warning.get("severity"), 0);
            entries.add(new Models.WarningEntry(code, name, severity));
        }
        return entries;
    }

    // ⚠️ A.A.S の警報イベントから発表中の名称だけを取り出す (表示用)
    public static LinkedHashSet<String> warningNamesFromA2s(Map<String, Object> event) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Models.WarningEntry entry : warningEntriesFromA2s(event)) names.add(entry.name());
        return names;
    }

    // ----------------------------------------
    // 🌦️ Open-Meteo (時間別・週間・現在の気象)
    // ----------------------------------------
    // 📝 Open-Meteo のモデル明示指定: 気温・天気など決定論的な変数は
    // 気象庁モデル (jma_seamless)、降水確率だけは JMA 系が返さない
    // ため ECMWF IFS (ecmwf_ifs025) で補完する複合指定。順序が意味を
    // 持つ (先頭モデルが current と応答格子を決める) ため入れ替え
    // 禁止。無指定 (best_match) への黙示フォールバックも禁止
    static final String OPEN_METEO_MODELS = "jma_seamless,ecmwf_ifs025";
    public static final String OPEN_METEO_SOURCE_NOTE =
            "予報: Open-Meteo（気温・天気: 気象庁モデル MSM/GSM、降水確率: ECMWF IFS）";

    // 🌐 Open-Meteo の取得 URL (現在の気象・時間別・週間を1リクエストに統合)
    static String openMeteoUrl(Models.Location loc) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + loc.lat() + "&longitude=" + loc.lon()
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&hourly=temperature_2m,precipitation_probability,weather_code,precipitation"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum"
                + "&forecast_days=7&timezone=Asia%2FTokyo"
                + "&models=" + OPEN_METEO_MODELS;
    }

    // ⌨️ 複合指定では hourly/daily のキーにモデル名の接尾辞が付く
    // (例 temperature_2m_jma_seamless)。接尾辞付きキーがあればそれを
    // 優先し、無い場合のみ接尾辞なしキーを見る (同一応答内のキー
    // 選択であり、best_match へのリクエスト自体のフォールバック
    // ではない)。降水確率の JMA 側キーは全て null のため、呼び出し
    // 側は必ず ECMWF 側キーを指定すること
    static List<Object> arrModel(Map<String, Object> parent, String suffixedKey, String plainKey) {
        if (parent.containsKey(suffixedKey)) return Json.arr(parent.get(suffixedKey));
        return Json.arr(parent.get(plainKey));
    }

    // 🌧️ 天気絵文字の決定 (純粋関数): 天気コードに加えて降水確率と時刻も考慮する。
    // - 晴れ/くもり系 (コード 0〜3) でも降水確率 50% 以上なら雨系にする
    //   (実機で「降水86% なのに ☀」という不整合が見つかったため)
    // - 夜間 (21時〜翌4時台) の晴れは ☀ ではなく 🌙 にする
    public static String weatherEmoji(int code, int popPct, int hour) {
        if (popPct >= 50 && code >= 0 && code <= 3) return popPct >= 80 ? "☔" : "🌧";
        boolean night = hour >= 21 || hour <= 4;
        if (night && (code == 0 || code == 1)) return "🌙";
        return wmoEmoji(code);
    }

    // 🌦️ WMO 天気コード → 絵文字 (時間別・週間予報のコンパクト表示用)
    static String wmoEmoji(int code) {
        return switch (code) {
            case 0 -> "☀";
            case 1 -> "🌤";
            case 2 -> "⛅";
            case 3 -> "☁";
            case 45, 48 -> "🌫";
            case 51, 53, 55, 56, 57 -> "🌦";
            case 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧";
            case 71, 73, 75, 77, 85, 86 -> "🌨";
            case 95, 96, 99 -> "⛈";
            default -> "・";
        };
    }

    // 📝 JSON の数値を Double で返す (欠落・null・数値でない場合は
    // null = 不明)。気温のように「不明なら null」と定義したフィー
    // ルド用 (0 を返すと 0℃ と表示されてしまう)
    static Double dblOrNull(Object value) {
        double parsed = Json.dbl(value, Double.NaN);
        return Double.isNaN(parsed) ? null : parsed;
    }

    // 📝 Open-Meteo hourly を「今日の残り＋明日」の3時間刻みで構造化する (最大10件)。
    // popIntervalMax は区間最大: その時刻から3時間の降水確率の最大値
    // (3時間サンプリングでピークを取りこぼさないため)
    public static List<Models.HourlyEntry> parseHourly(Object root, long nowMs) {
        List<Models.HourlyEntry> entries = new ArrayList<>();
        Map<String, Object> hourly = Json.obj(Json.obj(root).get("hourly"));
        List<Object> times = Json.arr(hourly.get("time"));
        List<Object> temps = arrModel(hourly, "temperature_2m_jma_seamless", "temperature_2m");
        List<Object> pops = arrModel(hourly, "precipitation_probability_ecmwf_ifs025", "precipitation_probability");
        List<Object> codes = arrModel(hourly, "weather_code_jma_seamless", "weather_code");
        List<Object> precips = arrModel(hourly, "precipitation_jma_seamless", "precipitation");
        java.time.ZoneId jst = java.time.ZoneId.of("Asia/Tokyo");
        java.time.LocalDateTime nowHour = java.time.Instant.ofEpochMilli(nowMs).atZone(jst).toLocalDateTime()
                .withMinute(0).withSecond(0).withNano(0);
        for (int i = 0; i < times.size() && entries.size() < 10; i++) {
            java.time.LocalDateTime time;
            try {
                time = java.time.LocalDateTime.parse(Json.str(times.get(i)));
            } catch (Exception e) {
                continue;
            }
            if (time.isBefore(nowHour) || time.getHour() % 3 != 0) continue;
            int code = codes.size() > i ? (int) Json.dbl(codes.get(i), -1) : -1;
            double popRaw = pops.size() > i ? Json.dbl(pops.get(i), -1) : -1;
            int pop = popRaw < 0 ? -1 : (int) Math.round(popRaw);
            Double temp = temps.size() > i ? dblOrNull(temps.get(i)) : null;
            // 🕐 1時間値を保持し、この時刻から3時間の区間最大を併記できるよう計算する
            int intervalMax = pop;
            for (int k = i + 1; k <= i + 2 && k < pops.size(); k++) {
                double peak = Json.dbl(pops.get(k), -1);
                if (peak >= 0) intervalMax = Math.max(intervalMax, (int) Math.round(peak));
            }
            Double precip = precips.size() > i ? dblOrNull(precips.get(i)) : null;
            entries.add(new Models.HourlyEntry(time.getMonthValue() + "/" + time.getDayOfMonth(), time.getHour(),
                    weatherEmoji(code, pop, time.getHour()),
                    WMO.getOrDefault(String.valueOf(code), "不明"), temp, pop, precip, intervalMax));
        }
        return entries;
    }

    // 🔄 Open-Meteo hourly を「今日の残り＋明日」の3時間刻みでコンパクトに整形する
    public static String formatHourly(Object root, long nowMs) {
        StringBuilder sb = new StringBuilder();
        String prevDate = null;
        List<Models.HourlyEntry> entries = parseHourly(root, nowMs);
        for (int k = 0; k < entries.size(); k++) {
            Models.HourlyEntry entry = entries.get(k);
            if (k > 0) sb.append(" / ");
            if (!entry.dateLabel().equals(prevDate)) {
                sb.append(entry.dateLabel()).append(" ");
                prevDate = entry.dateLabel();
            }
            sb.append(entry.hour()).append("時 ").append(entry.emoji()).append(" ")
                    .append(entry.tempC() == null ? "?" : Math.round(entry.tempC())).append("℃");
            if (entry.popPct() >= 0) sb.append(" 降水").append(entry.popPct()).append("%");
            // 🕐 サンプリング時刻より区間最大が高い場合は併記する
            if (entry.popIntervalMax() > Math.max(entry.popPct(), 0)) {
                sb.append("（3時間最大").append(entry.popIntervalMax()).append("%）");
            }
            // 🌧️ 降水量 (mm) も併記する
            if (entry.precipMm() != null && entry.precipMm() >= 0.05) {
                sb.append(" 降水量").append(fmtMm(entry.precipMm())).append("mm");
            }
        }
        return sb.toString();
    }

    // 🌧️ 降水量の表示整形 (小数1桁、末尾の .0 は省く)
    static String fmtMm(double mm) {
        double rounded = Math.round(mm * 10) / 10.0;
        return rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    // 🌧️ Open-Meteo daily (7日) を構造化する。絵文字はその日の降水確率最大値も考慮する
    public static List<Models.DailyEntry> parseWeekly(Object root) {
        List<Models.DailyEntry> entries = new ArrayList<>();
        Map<String, Object> daily = Json.obj(Json.obj(root).get("daily"));
        List<Object> times = Json.arr(daily.get("time"));
        List<Object> codes = arrModel(daily, "weather_code_jma_seamless", "weather_code");
        List<Object> maxs = arrModel(daily, "temperature_2m_max_jma_seamless", "temperature_2m_max");
        List<Object> mins = arrModel(daily, "temperature_2m_min_jma_seamless", "temperature_2m_min");
        List<Object> pops = arrModel(daily, "precipitation_probability_max_ecmwf_ifs025", "precipitation_probability_max");
        List<Object> precips = arrModel(daily, "precipitation_sum_jma_seamless", "precipitation_sum");
        for (int i = 0; i < times.size(); i++) {
            String date = Json.str(times.get(i)); // yyyy-MM-dd
            String label = date.length() >= 10
                    ? Integer.parseInt(date.substring(5, 7)) + "/" + Integer.parseInt(date.substring(8, 10)) : date;
            int code = codes.size() > i ? (int) Json.dbl(codes.get(i), -1) : -1;
            double popRaw = pops.size() > i ? Json.dbl(pops.get(i), -1) : -1;
            int pop = popRaw < 0 ? -1 : (int) Math.round(popRaw);
            Double min = mins.size() > i ? dblOrNull(mins.get(i)) : null;
            Double max = maxs.size() > i ? dblOrNull(maxs.get(i)) : null;
            Double precip = precips.size() > i ? dblOrNull(precips.get(i)) : null;
            entries.add(new Models.DailyEntry(label, weatherEmoji(code, pop, 12),
                    WMO.getOrDefault(String.valueOf(code), "不明"), min, max, pop, precip));
        }
        return entries;
    }

    // 🔄 Open-Meteo daily (7日) を週間予報の1行に整形する
    public static String formatWeekly(Object root) {
        List<Models.DailyEntry> entries = parseWeekly(root);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("週間： ");
        for (int i = 0; i < entries.size(); i++) {
            Models.DailyEntry entry = entries.get(i);
            if (i > 0) sb.append(" / ");
            sb.append(entry.dateLabel()).append(" ").append(entry.emoji()).append(" ")
                    .append(entry.minC() == null ? "?" : Math.round(entry.minC())).append("〜")
                    .append(entry.maxC() == null ? "?" : Math.round(entry.maxC())).append("℃");
            // 🌧️ 日降水量 (mm) も併記する
            if (entry.precipMm() != null && entry.precipMm() >= 0.05) {
                sb.append(" 降水量").append(fmtMm(entry.precipMm())).append("mm");
            }
        }
        return sb.toString();
    }

    // 🌐 表表示用: 地点の時間別・週間予報を構造化データで取得する (失敗時は空の表)
    public static Models.WeatherTables openMeteoTables(Models.Location loc) {
        try {
            Object root = Http.fetchJson(openMeteoUrl(loc));
            return new Models.WeatherTables(parseHourly(root, System.currentTimeMillis()), parseWeekly(root));
        } catch (Exception e) {
            return new Models.WeatherTables(List.of(), List.of());
        }
    }

    // ----------------------------------------
    // 🌦️ 地点の天気テキスト (予報＋現在の気象＋警報)
    // ----------------------------------------
    public static String weatherText(Models.Location loc) {
        // 🌦️ 気象庁予報を先に取得する: 予報テキストに加え、降水確率・
        // 気温の照合に使う「一次細分区域コード」をこの JSON から得る。
        // 警報の地域判定は A.A.S が返す区域情報 (prefName / areaName) で行う
        String[] forecast = forecastPart(loc); // [0]=予報テキスト [1]=細分区域コード
        Future<String> currentFuture = Http.FETCH_POOL.submit(() -> currentPart(loc));
        Future<String> warningFuture = Http.FETCH_POOL.submit(() -> warningPart(loc));
        StringBuilder sb = new StringBuilder();
        sb.append(forecast[0]);
        try {
            sb.append(currentFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception e) {
            sb.append("（現在の気象を取得できませんでした）\n");
        }
        try {
            sb.append(warningFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // 📝 [予報テキスト, 一次細分区域コード] を返す
    private static String[] forecastPart(Models.Location loc) {
        StringBuilder sb = new StringBuilder();
        String areaCode = "";
        try {
            Object root = Http.fetchJson("https://www.jma.go.jp/bosai/forecast/data/forecast/" + loc.prefCode() + ".json");
            String[] weather = parseForecast(root, loc.areaName());
            areaCode = forecastAreaCode(root, loc.areaName());
            if (weather.length > 0) {
                sb.append("今日： ").append(weather[0]);
                if (weather.length > 1) sb.append("\n明日： ").append(weather[1]);
                if (weather.length > 2) sb.append("\n明後日： ").append(weather[2]);
                sb.append("\n");
                if (areaCode.isEmpty()) sb.append("（※地域名が一致しないため、県内の先頭地域の予報を表示しています）\n");
            }
            String pops = parsePops(root, areaCode);
            if (!pops.isEmpty()) sb.append(pops).append("\n");
            String temps = parseTemps(root, areaCode);
            if (!temps.isEmpty()) sb.append(temps).append("\n");
        } catch (Exception e) {
            sb.append("（天気予報を取得できませんでした：").append(Http.friendlyError(e)).append("）\n");
        }
        return new String[]{sb.toString(), areaCode};
    }

    private static String currentPart(Models.Location loc) {
        StringBuilder sb = new StringBuilder();
        try {
            Map<String, Object> root = Json.obj(Http.fetchJson(openMeteoUrl(loc)));
            Map<String, Object> current = Json.obj(root.get("current"));
            Map<String, Object> daily = Json.obj(root.get("daily"));
            String description = WMO.getOrDefault(String.valueOf((int) Json.dbl(current.get("weather_code"), 0)), "不明");
            List<Object> pops = arrModel(daily, "precipitation_probability_max_ecmwf_ifs025", "precipitation_probability_max");
            double pop = pops.isEmpty() ? -1 : Json.dbl(pops.get(0), -1);
            List<Object> mins = arrModel(daily, "temperature_2m_min_jma_seamless", "temperature_2m_min");
            List<Object> maxs = arrModel(daily, "temperature_2m_max_jma_seamless", "temperature_2m_max");
            sb.append("現在：").append(description).append(" ").append(Json.dbl(current.get("temperature_2m"), 0)).append("℃ / 風 ")
                    .append(Json.dbl(current.get("wind_speed_10m"), 0)).append("km/h / 今日 ")
                    .append(mins.isEmpty() ? "?" : Json.dbl(mins.get(0), 0)).append("〜")
                    .append(maxs.isEmpty() ? "?" : Json.dbl(maxs.get(0), 0)).append("℃ / 降水確率 最大")
                    .append(pop < 0 ? "?" : String.valueOf((long) pop)).append("%\n");
            if (pop >= 80) sb.append("▶ 降水確率が高め。大きめの傘が安心です\n");
            List<Object> dailyCodes = arrModel(daily, "weather_code_jma_seamless", "weather_code");
            if (dailyCodes.size() > 1 && mins.size() > 1 && maxs.size() > 1) {
                double popTomorrow = pops.size() > 1 ? Json.dbl(pops.get(1), -1) : -1;
                sb.append("明日（Open-Meteo）: ")
                        .append(WMO.getOrDefault(String.valueOf((int) Json.dbl(dailyCodes.get(1), 0)), "不明")).append(" ")
                        .append(Json.dbl(mins.get(1), 0)).append("〜").append(Json.dbl(maxs.get(1), 0))
                        .append("℃ / 降水確率 最大").append(popTomorrow < 0 ? "?" : String.valueOf((long) popTomorrow)).append("%\n");
            }
            String hourly = formatHourly(root, System.currentTimeMillis());
            if (!hourly.isEmpty()) sb.append("🕒 時間別： ").append(hourly).append("\n");
            String weekly = formatWeekly(root);
            if (!weekly.isEmpty()) sb.append(weekly).append("\n");
            // 🌦️ 出典モデルを併記する (気象庁 bosai 由来の値と混同しないため)
            sb.append(OPEN_METEO_SOURCE_NOTE).append("\n");
        } catch (Exception e) {
            sb.append("（現在の気象を取得できませんでした：").append(Http.friendlyError(e)).append("）\n");
        }
        return sb.toString();
    }

    // ⚠️ 警報パートは A.A.S 経由。A.A.S 未設定・到達不能・対象区域に
    // 発表が無ければ何も付けない (天気予報など他の表示に影響しない)
    private static String warningPart(Models.Location loc) {
        StringBuilder sb = new StringBuilder();
        try {
            if (!A2s.enabled()) return "";
            A2s.refreshFromEvents("warning", A2s.WARNING_CACHE_MAX);
            Map<String, Object> event = A2s.warningEventForLocation(loc);
            LinkedHashSet<String> names = warningNamesFromA2s(event);
            if (!names.isEmpty()) {
                sb.append("⚠ 発表中： ").append(String.join("、", names));
                String reportDatetime = Json.str(event.get("reportDatetime")); // 2026-11-17T11:00:00+09:00
                if (reportDatetime.length() >= 16) {
                    sb.append("（").append(reportDatetime.substring(5, 10).replace('-', '/'))
                            .append(" ").append(reportDatetime.substring(11, 16)).append("発表）");
                }
                sb.append("\n");
                String headline = Json.str(event.get("headline"));
                if (!headline.isEmpty() && !headline.contains("解除")) sb.append("　").append(headline).append("\n");
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    // ----------------------------------------
    // 🌀 台風 (A.A.S 経由)
    // ----------------------------------------
    // A.A.S の台風イベント (台風ごとに1件) からセクションを作る。
    // 台風が無ければ null。表示は A.A.S が用意した表示行 (lines) を
    // そのまま使い、lines が無いイベントは名称・英語名・番号から
    // 最低限の行を組む
    public static Models.Section typhoonFromA2s(List<Map<String, Object>> events) {
        if (events == null || events.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> event : events) {
            List<String> lines = new ArrayList<>();
            for (Object lineObj : Json.arr(event.get("lines"))) {
                String line = Json.str(lineObj).trim();
                if (!line.isEmpty()) lines.add(line);
            }
            if (lines.isEmpty()) {
                List<String> picked = new ArrayList<>();
                for (String key : List.of("name", "englishName", "number")) {
                    String value = Json.str(event.get(key)).trim();
                    if (!value.isEmpty() && !picked.contains(value)) picked.add(value);
                }
                if (!picked.isEmpty()) lines.add("・" + String.join(" ", picked));
            }
            for (String line : lines) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("🌀 ").append(line.startsWith("・") ? line.substring(1) : line);
            }
        }
        if (sb.length() == 0) return null;
        return new Models.Section("🌀 台風情報", sb.toString(), "warn");
    }

    public static Models.Section typhoonSection() {
        try {
            if (!A2s.enabled()) return null;
            A2s.refreshFromEvents("typhoon", A2s.TYPHOON_CACHE_MAX);
            return typhoonFromA2s(A2s.typhoonEvents());
        } catch (Exception e) {
            return null;
        }
    }
}
