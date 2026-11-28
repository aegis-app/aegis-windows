package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

// ========================================
// 🚨 Eew (緊急地震速報: A.A.S 経由)
// ========================================
// 📌 取得は A.A.S のみ (完全一本化)。ここでのパースは A.A.S 変換
// 済みの内部共通形式に対する処理。地点別予想は A.A.S では生成
// されないため、材料の areaScales (scaleTo 込み) と areaArrivals
// から従来と同じ導出を行う。
public final class Eew {
    private Eew() {}

    // 🚨 EEW の鮮度: 従来の「1時間以内」では揺れが終わった後も最大
    // 1時間 danger 表示が残るため、表示窓を10分に短縮し、3分を超え
    // たら danger から warn に格下げして経過を明記する
    public static final long DANGER_MS = 3 * 60 * 1000L;
    public static final long DISPLAY_MS = 10 * 60 * 1000L;

    // 🚨 EEW のうち「実データがあり、nowMs から表示窓 (10分) 以内」
    // の最新レコードを返す。それ以外は null
    static Map<String, Object> freshItem(Object root, long nowMs) {
        for (Object itemObj : Json.arr(root)) {
            Map<String, Object> item = Json.obj(itemObj);
            Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
            List<Object> areas = Json.arr(item.get("areas"));
            if (earthquake.isEmpty() && areas.isEmpty()) continue; // メタデータのみのレコードは読み飛ばす
            long timeMs = Http.parseP2pTimeMs(Json.str(item.get("time")));
            if (timeMs < 0) continue;
            if (nowMs - timeMs > DISPLAY_MS) return null; // 古い EEW は出さない
            return item;
        }
        return null;
    }

    // ----------------------------------------
    // 🚨 形式判定・フィールド読み取り (内部共通形式)
    // ----------------------------------------
    // A2s.toEewItem が作る内部共通形式: isCancel / isWarn / isFinal /
    // serial / eventId を item 直下に持ち、earthquake に maxScale
    // (推定最大震度) と hypocenter、areas は scale (下限) / scaleTo
    // (上限) を持つ。旧形式のキー (cancelled / scaleFrom / issue) も
    // 防御的に受ける

    // 📝 旧形式の item かどうか (cancelled または issue を持つ)
    static boolean isLegacyFormat(Map<String, Object> item) {
        return item.containsKey("cancelled") || item.get("issue") instanceof Map;
    }

    // 📝 取消かどうか: 内部共通形式は isCancel、旧形式は cancelled
    static boolean cancelled(Map<String, Object> item) {
        return item.containsKey("cancelled") ? Json.bool(item.get("cancelled")) : Json.bool(item.get("isCancel"));
    }

    // 📡 警報かどうか: 変換は isWarn を明示する。無いものは予報として扱う
    static boolean isWarn(Map<String, Object> item) {
        return item.containsKey("isWarn") && Json.bool(item.get("isWarn"));
    }

    // 🌏 区域の予想震度 (下限): 内部共通形式は scale、旧形式は
    // scaleFrom。scaleTo=99 は「〜程度以上」を表すため下限としては
    // 採用しない
    static int areaScale(Map<String, Object> area) {
        int scale = (int) Json.dbl(area.get("scale"), -1);
        if (scale <= 0) scale = (int) Json.dbl(area.get("scaleFrom"), -1);
        return scale;
    }

    // 🌏 区域の予想震度の上限 (scaleTo)。99 は「〜程度以上」、無ければ -1
    static int areaScaleTo(Map<String, Object> area) {
        return (int) Json.dbl(area.get("scaleTo"), -1);
    }

    // 🌏 区域の予想震度範囲の表示文 (下限〜上限。scaleTo=99 は「程度以上」)
    static String scaleRangeText(int scale, int scaleTo) {
        if (scale <= 0) return "";
        if (scaleTo == 99) return "震度" + Quakes.scaleToStr(scale) + "程度以上";
        if (scaleTo > 0 && scaleTo != scale) {
            return "震度" + Quakes.scaleToStr(scale) + "〜" + Quakes.scaleToStr(scaleTo);
        }
        return "震度" + Quakes.scaleToStr(scale);
    }

    // ----------------------------------------
    // 🔑 同一視キー
    // ----------------------------------------
    // 📍 同一視キー: 発生時刻＋震央座標の丸め (小数1桁)、座標が
    // 無ければ発生時刻＋震源名。A.A.S の baseKey と一致する
    private static String format1(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    public static String baseKeyOfItem(Map<String, Object> item) {
        Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
        Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
        String origin = Json.str(earthquake.get("originTime"));
        if (origin.isEmpty()) origin = Json.str(item.get("time"));
        double lat = Json.dbl(hypocenter.get("latitude"), Double.NaN);
        double lon = Json.dbl(hypocenter.get("longitude"), Double.NaN);
        if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
            return "eew-" + origin + "@" + format1(lat) + "," + format1(lon);
        }
        return "eew-" + origin + "-" + Json.str(hypocenter.get("name"), "不明");
    }

    public static String keyOfItem(Map<String, Object> item) {
        return baseKeyOfItem(item) + (cancelled(item) ? "-cancel" : "");
    }

    // ----------------------------------------
    // 🌏 推定値の読み取り
    // ----------------------------------------
    // 🌏 推定最大震度: maxScale があればそれ、無ければ areas の下限の最大値で代用する
    static int estScale(Map<String, Object> item) {
        Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
        int maxScale = (int) Json.dbl(earthquake.get("maxScale"), -1);
        if (maxScale > 0) return maxScale;
        int best = -1;
        for (Object areaObj : Json.arr(item.get("areas"))) {
            best = Math.max(best, areaScale(Json.obj(areaObj)));
        }
        return best;
    }

    // 🚨 EEW の行動文言 (丁寧体) の出し分け: 推定最大震度が小さい
    // 予報 (震度3程度) でも「強い揺れに備える」文言を出すと過剰に
    // なるため、震度で3段階に分ける。震度不明 (-1) は、警報なら
    // 強い揺れ前提で強い文言、予報なら注意喚起のみにする
    static String actionGuide(int estScale, boolean warn) {
        if (estScale >= 45) return "強い揺れに備え、頭を守り安全な場所へ移動してください。";
        if (estScale >= 40) return "やや強い揺れに備えてください。落下物に注意し、頭を守ってください。";
        if (estScale > 0) return "揺れに注意してください。";
        return warn ? "強い揺れに備え、頭を守り安全な場所へ移動してください。" : "揺れに注意してください。";
    }

    // 📡 報番号: 内部共通形式は serial (旧形式の issue.serial も受ける)
    static int serial(Map<String, Object> item) {
        int value = (int) Json.dbl(Json.obj(item.get("issue")).get("serial"), -1);
        if (value <= 0) value = (int) Json.dbl(item.get("serial"), -1);
        return value;
    }

    // 🌏 仮定震源要素かどうか: 内部共通形式は isAssumption
    // (旧形式の earthquake.condition も受ける)
    static boolean isAssumption(Map<String, Object> item) {
        if (item.containsKey("isAssumption")) return Json.bool(item.get("isAssumption"));
        return Json.str(Json.obj(item.get("earthquake")).get("condition")).contains("仮定震源要素");
    }

    // ----------------------------------------
    // 🖥️ 表示用セクション
    // ----------------------------------------
    // 🚨 緊急地震速報: 実データのある最新報が表示窓以内なら Section を返す。それ以外は null
    public static Models.Section parseEew(Object root) {
        return parseEew(root, System.currentTimeMillis());
    }

    static Models.Section parseEew(Object root, long nowMs) {
        Map<String, Object> item = freshItem(root, nowMs);
        return item == null ? null : sectionFromItem(item, nowMs);
    }

    // 🚨 EEW 1件から表示用セクションを作る (新しさの判定は呼び出し側で行う)
    static Models.Section sectionFromItem(Map<String, Object> item) {
        return sectionFromItem(item, System.currentTimeMillis());
    }

    static Models.Section sectionFromItem(Map<String, Object> item, long nowMs) {
        Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
        List<Object> areas = Json.arr(item.get("areas"));
        String time = Json.str(item.get("time"));
        if (cancelled(item)) {
            return new Models.Section("🚨 緊急地震速報",
                    "緊急地震速報は取り消されました（" + time.substring(5, 16) + "）", "info");
        }
        // 📡 見出しには予報/警報の区別を出す (isWarn=true が警報)
        String title = "🚨 緊急地震速報" + (isWarn(item) ? "（警報）" : "（予報）");
        StringBuilder sb = new StringBuilder();
        if (Json.bool(item.get("isFinal"))) sb.append("（最終報）");
        if (!earthquake.isEmpty()) {
            // 🚨 EEW の震源・マグニチュード・深さ・最大震度はすべて
            // 推定値。確定値と混同しないよう各項目に「推定」を明示する
            Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
            if (!hypocenter.isEmpty()) sb.append("推定震源 ").append(Json.str(hypocenter.get("name"), "不明"));
            double estMagnitude = Json.dbl(hypocenter.get("magnitude"), -1);
            if (estMagnitude >= 0) sb.append(" / 推定マグニチュード M").append(estMagnitude);
            double estDepth = Json.dbl(hypocenter.get("depth"), -999);
            if (estDepth == 0) sb.append(" / 推定深さ ごく浅い");
            else if (estDepth > 0) sb.append(" / 推定深さ 約").append((int) estDepth).append("km");
            int est = estScale(item);
            if (est > 0) sb.append(" / 推定最大震度 ").append(Quakes.scaleToStr(est));
            int serialNumber = serial(item);
            if (serialNumber > 0) sb.append(" / 第").append(serialNumber).append("報");
            if (isAssumption(item)) sb.append(" / 仮定震源要素による推定");
        }
        if (!areas.isEmpty()) {
            LinkedHashSet<String> prefs = new LinkedHashSet<>();
            for (Object areaObj : areas) {
                if (prefs.size() >= 6) break;
                String pref = Json.str(Json.obj(areaObj).get("pref"));
                if (!pref.isEmpty()) prefs.add(pref);
            }
            if (!prefs.isEmpty()) sb.append("\n対象: ").append(String.join("、", prefs));
            // 🌏 地域別予想震度 (推定): area ごとの予想震度を明示する
            List<String> areaScales = new ArrayList<>();
            for (Object areaObj : areas) {
                if (areaScales.size() >= 8) break;
                Map<String, Object> area = Json.obj(areaObj);
                int scale = areaScale(area);
                String name = Json.str(area.get("name"));
                // 📳 上限 (scaleTo) があれば範囲として出す (99 は「程度以上」)
                if (scale > 0 && !name.isEmpty()) {
                    areaScales.add(name + " " + scaleRangeText(scale, areaScaleTo(area)));
                }
            }
            if (!areaScales.isEmpty()) sb.append("\n地域別予想震度（推定）: ").append(String.join("、", areaScales));
            sb.append("\n※ EEWの震源・マグニチュード・深さ・最大震度・地域別震度・到達予想はすべて推定値です。確定値は地震情報で確認してください");
        }
        // 🚨 発表から DANGER_MS を超えた報は danger から warn に格下げする
        long issuedMs = Http.parseP2pTimeMs(time);
        long ageMs = issuedMs > 0 ? nowMs - issuedMs : 0;
        if (ageMs > DANGER_MS) {
            sb.append("\n（発表から約").append(ageMs / 60_000L)
                    .append("分が経過しています。揺れは収まっている可能性があります）");
            return new Models.Section(title, sb.toString(), "warn");
        }
        sb.append("\n").append(actionGuide(estScale(item), isWarn(item)));
        return new Models.Section(title, sb.toString(), "danger");
    }

    // 🚨 表示・通知に使う現在の EEW 1件を決める。取得は A.A.S のみ:
    // SSE の保持イベントから取り出し、履歴参照時は REST でも回収
    // する。A.A.S 未設定・未受信・表示窓外なら null。取得失敗時も
    // 例外にせず null を返し、EEW なし扱いで他機能を落とさない
    static Map<String, Object> currentItem(long nowMs) {
        A2s.refreshFromEvents("eew", 5);
        return A2s.latestEewItem(nowMs);
    }

    public static Models.Section eewSection() {
        long now = System.currentTimeMillis();
        Map<String, Object> item = currentItem(now);
        return item == null ? null : sectionFromItem(item, now);
    }

    // ----------------------------------------
    // 🚨 揺れの到達カウントダウン
    // ----------------------------------------
    // estimated=true は S波速度からの推定 (arrivalTime が無い場合)

    // 🌏 S波 (3.5km/s) による主要動到達予想: 発生時刻 + 震源距離/3.5 秒
    static long estimateArrivalMs(long originMs, double epiLat, double epiLon, double depthKm, Models.Location loc) {
        double surfaceKm = Geo.haversineKm(loc.lat(), loc.lon(), epiLat, epiLon);
        double hypoKm = Math.sqrt(surfaceKm * surfaceKm + depthKm * depthKm);
        return originMs + (long) (hypoKm / 3.5 * 1000);
    }

    // 📍 登録地点ごとの到達予想を作る。対象は地点の都道府県に一致
    // する area。area に arrivalTime があればそれを使い、無ければ
    // S波速度で推定する (推定と明記できるよう印を付ける)
    public static List<Models.EewArrival> parseArrivals(Object root, Models.Location[] locations, long nowMs) {
        List<Models.EewArrival> arrivals = new ArrayList<>();
        Map<String, Object> item = freshItem(root, nowMs);
        if (item == null || cancelled(item)) return arrivals;
        Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
        Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
        double epiLat = Json.dbl(hypocenter.get("latitude"), 0);
        double epiLon = Json.dbl(hypocenter.get("longitude"), 0);
        double depth = Json.dbl(hypocenter.get("depth"), 0);
        if (depth < 0) depth = 0;
        long originMs = Http.parseP2pTimeMs(
                Json.str(earthquake.get("originTime"), Json.str(earthquake.get("time"), Json.str(item.get("time")))));
        List<Object> areas = Json.arr(item.get("areas"));
        for (Models.Location loc : locations) {
            String pref = Geo.prefName(loc.prefCode());
            if (pref.isEmpty()) continue;
            Map<String, Object> matched = null;
            int bestScore = -1;
            for (Object areaObj : areas) {
                Map<String, Object> area = Json.obj(areaObj);
                if (!pref.equals(Json.str(area.get("pref")))) continue;
                // 📝 区域の選び方: 地域区分名の一致を最優先 (+2) し、
                // 到達予想時刻 (arrivalTime) の有無を次点 (+1) とする。
                // 「先頭の区域を採り、名前一致で打ち切る」方式だと、
                // arrivalTime を持つ区域があるのに S波推定に落ちたり、
                // 名前は一致しても到達時刻を持たない区域を掴んだりする
                int score = (!loc.areaName().isEmpty() && Json.str(area.get("name")).endsWith(loc.areaName()) ? 2 : 0)
                        + (Http.parseP2pTimeMs(Json.str(area.get("arrivalTime"))) > 0 ? 1 : 0);
                if (score > bestScore) {
                    bestScore = score;
                    matched = area;
                }
            }
            if (matched == null) continue;
            long arrivalMs = Http.parseP2pTimeMs(Json.str(matched.get("arrivalTime")));
            if (arrivalMs > 0) {
                arrivals.add(new Models.EewArrival(loc.name(), arrivalMs, false));
            } else if (originMs > 0 && epiLat != 0) {
                arrivals.add(new Models.EewArrival(loc.name(), estimateArrivalMs(originMs, epiLat, epiLon, depth, loc), true));
            }
        }
        return arrivals;
    }

    // 📍 地点別予想震度を areaScales から導出する。A.A.S は地点の
    // 概念を持たないため生成されず、クライアント側の登録地点で
    // 照合する: 地域区分名が一致する区域を優先し、無ければ同一
    // 都道府県の区域の最大値をその地点の予想震度とする。対象区域
    // が無い地点は結果に含めない (予想を作らない)
    static Map<String, Integer> localForecastScales(Map<String, Object> item, Models.Location[] locations) {
        Map<String, Integer> forecasts = new LinkedHashMap<>();
        if (item == null) return forecasts;
        List<Object> areas = Json.arr(item.get("areas"));
        for (Models.Location loc : locations) {
            String pref = Geo.prefName(loc.prefCode());
            if (pref.isEmpty()) continue;
            int bestByName = -1;
            int bestByPref = -1;
            for (Object areaObj : areas) {
                Map<String, Object> area = Json.obj(areaObj);
                if (!pref.equals(Json.str(area.get("pref")))) continue;
                int scale = areaScale(area);
                if (scale <= 0) continue;
                bestByPref = Math.max(bestByPref, scale);
                if (!loc.areaName().isEmpty() && Json.str(area.get("name")).endsWith(loc.areaName())) {
                    bestByName = Math.max(bestByName, scale);
                }
            }
            int best = bestByName > 0 ? bestByName : bestByPref;
            if (best > 0) forecasts.put(loc.name(), best);
        }
        return forecasts;
    }

    // 🚨 EEW の表示用セクションと到達予想をまとめて取得する
    // (取得失敗・新鮮な EEW なしは null)
    public static Models.EewInfo eewInfo() {
        long now = System.currentTimeMillis();
        Map<String, Object> item = currentItem(now);
        if (item == null) return null;
        return new Models.EewInfo(sectionFromItem(item, now), parseArrivals(List.of(item), Geo.effectiveLocations(), now));
    }
}
