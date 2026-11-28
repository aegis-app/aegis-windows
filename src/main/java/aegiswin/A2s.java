package aegiswin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// ========================================
// 📡 A2s (A.A.S / Aegis Alert System 連携)
// ========================================
// 📌 A.A.S は地震・津波・EEW を P2P / Wolfx から、気象警報・注意報と
// 台風を気象庁から取り込み・正規化して配信する中継ハブ
// (aegis-app/aegis-alert-system)。
// 📌 情報源の一本化 (ユーザーの明示決定): 地震・津波・EEW・警報・
// 台風の情報源は A.A.S のみとし、直接取得とフォールバックは持たない。
// 天気予報そのもの・レーダー・ニュースなど対象外の機能は直接取得する。
// - A.A.S 由来のイベントは種別ごとの変換で内部形式 (551 / 552 相当の
//   item、EEW の内部共通形式) にそろえ、既存の同一性キー・マージ規則
//   で表示・通知へ流す
// - 主経路は SSE (GET /v1/stream)。接続直後に保持イベントが古い順に
//   再生され、最後に event: ready が届く。以後は新規・更新が push される
// - 履歴参照 (一覧表示・通知チェック) は REST (GET /v1/events) で
//   補い、ストアへ取り込んでから取り出す (SSE 未接続時の回収も兼ねる)
// ⚠️ 再生ガード: ready より前の再生イベントは初期同期として状態へ
// マージするだけで、新規イベントとしては扱わない (再接続のたびに
// 過去イベントで通知や再描画が暴走するのを防ぐ)
// 📝 重複判定は key + updatedAt (EEW の同一性は baseKey)。内容が
// 変わらない再取り込みでは updatedAt が変わらないため、同じイベント
// は1回だけ受理する
// ⚙️ 有効化は設定式: URL とトークンの両方が指定されたときだけ有効。
// 指定元は設定タブ (Preferences の a2s_url / a2s_token) で、環境変数
// A2S_URL / A2S_TOKEN があればそちらを優先する。未設定・到達不能時は
// 対象情報は出ない (仕様) が、失敗でアプリや他機能を落とすことはない
public final class A2s {
    private A2s() {}

    // 📡 SSE 再接続の待機 (指数バックオフ、上限60秒)
    static final long[] SSE_RECONNECT_DELAYS_MS = {5000, 10000, 30000, 60000};

    // 💾 キャッシュの保持上限 (A.A.S 側の保持は72時間。表示・通知に
    // 使う直近分で足りる)。警報は「現在発表中の区域」の有効集合で、
    // A.A.S 側の種別上限 300 に合わせる
    static final int QUAKE_CACHE_MAX = 30;
    static final int EEW_CACHE_MAX = 5;
    static final int TSUNAMI_CACHE_MAX = 5;
    static final int WARNING_CACHE_MAX = 300;
    static final int TYPHOON_CACHE_MAX = 10;

    // ----------------------------------------
    // ⚙️ 設定 (URL とトークン)
    // ----------------------------------------
    // 設定タブ (Preferences) の値。Main が起動時と保存時に設定する
    public static volatile String prefUrl = "";
    public static volatile String prefToken = "";

    private static String envOr(String envName, String prefValue) {
        String value = System.getenv(envName);
        if (value != null && !value.isBlank()) return value.trim();
        return prefValue == null ? "" : prefValue.trim();
    }

    // 🌐 A.A.S のベース URL (末尾のスラッシュは取り除く)。未指定なら空文字
    public static String baseUrl() {
        String url = envOr("A2S_URL", prefUrl);
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    // 🔑 認証トークン (Authorization: Bearer)。未指定なら空文字
    public static String token() {
        return envOr("A2S_TOKEN", prefToken);
    }

    // 📡 A.A.S が有効か (URL とトークンの両方が指定されたときだけ有効、既定は無効)
    public static boolean enabled() {
        return !baseUrl().isEmpty() && !token().isEmpty();
    }

    // ----------------------------------------
    // 🔍 受信イベントの最小検証
    // ----------------------------------------
    // 必須フィールドの欠けたデータを変換に回すと、変換の失敗なのか
    // 上流の差異なのか区別できなくなるため、形が崩れているものは
    // ここで弾く
    static boolean isA2sEvent(Map<String, Object> event) {
        if (event == null || Json.str(event.get("key")).isEmpty()) return false;
        return switch (Json.str(event.get("kind"))) {
            case "quake" -> !Json.str(event.get("time")).isEmpty()
                    && event.get("maxScale") instanceof Number
                    && event.get("points") instanceof List
                    && event.get("prefScales") instanceof List;
            case "eew" -> !Json.str(event.get("baseKey")).isEmpty()
                    && !Json.str(event.get("originTime")).isEmpty()
                    && event.get("estScale") instanceof Number
                    && event.get("isWarn") instanceof Boolean
                    && event.get("cancel") instanceof Boolean;
            case "tsunami" -> event.get("grade") instanceof String
                    && event.get("areas") instanceof List;
            // ⚠️ 警報: 一次細分区域ごとに1件。解除イベント (warnings: [] /
            // maxSeverity: 0 / released: true) もこの形を満たす
            case "warning" -> !Json.str(event.get("officeCode")).isEmpty()
                    && !Json.str(event.get("areaCode")).isEmpty()
                    && event.get("warnings") instanceof List
                    && event.get("maxSeverity") instanceof Number;
            // 🌀 台風: 台風ごとに1件。表示行 (lines) が本体
            case "typhoon" -> event.get("lines") instanceof List
                    && !Json.arr(event.get("lines")).isEmpty();
            default -> false;
        };
    }

    // ----------------------------------------
    // 🔀 変換: A.A.S イベント → 内部形式
    // ----------------------------------------
    // 変換は「A.A.S が持っている情報だけ」で正直に行い、値を補完しない

    // 🌏 地震 → 551 相当の item 形式。統合済みのイベントを
    // Quakes.parseEarthquakes のグルーピング・マージへそのまま流す。
    // 通知 ID は電文 ID (telegramId) 由来とする
    static Map<String, Object> toQuakeItem(Map<String, Object> event) {
        Map<String, Object> hypocenter = new LinkedHashMap<>();
        hypocenter.put("name", Json.str(event.get("hypocenter")));
        hypocenter.put("magnitude", Json.dbl(event.get("magnitude"), -1)); // null は不明 (-1)
        hypocenter.put("depth", Json.dbl(event.get("depthKm"), -1)); // null は不明 (-1)、0 は「ごく浅い」
        if (event.get("lat") instanceof Number && event.get("lon") instanceof Number) {
            hypocenter.put("latitude", ((Number) event.get("lat")).doubleValue());
            hypocenter.put("longitude", ((Number) event.get("lon")).doubleValue());
        }
        Map<String, Object> earthquake = new LinkedHashMap<>();
        earthquake.put("time", Json.str(event.get("time")));
        // 🕐 発生時刻の原表記 (国外地震のみ)。国外地震では time が発表
        // 時刻側に倒れるため、発生時刻の表示にはこちらを使う
        String originTime = Json.str(event.get("originTime"));
        if (!originTime.isEmpty()) earthquake.put("originTime", originTime);
        earthquake.put("maxScale", (long) Json.dbl(event.get("maxScale"), -1));
        earthquake.put("domesticTsunami", Json.str(event.get("tsunamiKind"), "Unknown"));
        earthquake.put("hypocenter", hypocenter);
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("type", Json.str(event.get("issueType")));
        String correct = Json.str(event.get("issueCorrect"));
        if (!correct.isEmpty()) issue.put("correct", correct);
        issue.put("time", Json.str(event.get("announcedTime")));
        // 📍 観測点・地域点は両方とも points に入れる。地域代表値
        // (isArea=true) を落とすと、震度速報段階 (観測点なし・地域点
        // のみ) の地震で県別最大震度が導出できなくなる。isArea の印は
        // 保持し、観測点情報 (実測) の集計側で除外する
        List<Object> points = new ArrayList<>();
        for (Object pointObj : Json.arr(event.get("points"))) {
            Map<String, Object> point = Json.obj(pointObj);
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("pref", Json.str(point.get("pref")));
            converted.put("addr", Json.str(point.get("name")));
            converted.put("scale", (long) Json.dbl(point.get("scale"), 0));
            if (Json.bool(point.get("isArea"))) converted.put("isArea", true);
            points.add(converted);
        }
        // 📍 都道府県別の最大震度 (prefScales) も別枠で必ず持ち込む。
        // 地域点が欠ける場合でも県別最大震度をマージ側で導出できる
        List<Object> prefScales = new ArrayList<>();
        for (Object prefObj : Json.arr(event.get("prefScales"))) {
            Map<String, Object> prefScale = Json.obj(prefObj);
            if (Json.str(prefScale.get("pref")).isEmpty()) continue;
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("pref", Json.str(prefScale.get("pref")));
            converted.put("scale", (long) Json.dbl(prefScale.get("scale"), 0));
            prefScales.add(converted);
        }
        // 📍 points が空で prefScales だけがある場合は、県別最大を
        // 地域点 (isArea=true、addr=都道府県名) として補う
        if (points.isEmpty()) {
            for (Object prefObj : prefScales) {
                Map<String, Object> prefScale = Json.obj(prefObj);
                Map<String, Object> converted = new LinkedHashMap<>();
                converted.put("pref", Json.str(prefScale.get("pref")));
                converted.put("addr", Json.str(prefScale.get("pref")));
                converted.put("scale", Json.dbl(prefScale.get("scale"), 0));
                converted.put("isArea", true);
                points.add(converted);
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", Json.str(event.get("telegramId")));
        item.put("time", Json.str(event.get("announcedTime")));
        item.put("issue", issue);
        item.put("earthquake", earthquake);
        item.put("points", points);
        item.put("prefScales", prefScales);
        // 🌊 電文コメントの原文 (国外地震の津波コメント等)。無ければ付けない
        String tsunamiText = Json.str(event.get("tsunamiText"));
        if (!tsunamiText.isEmpty()) item.put("tsunamiText", tsunamiText);
        if (Json.bool(event.get("foreign"))) item.put("foreign", true);
        return item;
    }

    // 🚨 EEW → EEW の内部共通形式。同一視キーは Eew.baseKeyOfItem
    // (発生時刻＋震央座標の丸め) で A.A.S の baseKey と一致する。
    // 地点別予想は A.A.S では生成されないため、材料の areaScales
    // (scaleTo 込み) と areaArrivals から表示・到達予想側が導出する
    static Map<String, Object> toEewItem(Map<String, Object> event) {
        Map<String, Object> hypocenter = new LinkedHashMap<>();
        hypocenter.put("name", Json.str(event.get("hypocenter")));
        hypocenter.put("magnitude", Json.dbl(event.get("estMagnitude"), -1)); // null は不明 (-1)
        hypocenter.put("depth", Json.dbl(event.get("depthKm"), -1)); // null は不明 (-1)、0 は「ごく浅い」
        if (event.get("lat") instanceof Number && event.get("lon") instanceof Number) {
            hypocenter.put("latitude", ((Number) event.get("lat")).doubleValue());
            hypocenter.put("longitude", ((Number) event.get("lon")).doubleValue());
        }
        Map<String, Object> earthquake = new LinkedHashMap<>();
        earthquake.put("time", Json.str(event.get("originTime")));
        earthquake.put("originTime", Json.str(event.get("originTime")));
        earthquake.put("maxScale", (long) Json.dbl(event.get("estScale"), -1));
        earthquake.put("hypocenter", hypocenter);
        // 📳 地域別予想震度 (areaScales) を areas の本体にし、都道府県
        // ごとの到達予想 (areaArrivals) は同じ都道府県の area へ載せる。
        // 到達予想だけある都道府県は到達予想専用の area として足す
        // (表示側の震度表示は名前が空のものを自動で省く)
        List<Object> areas = new ArrayList<>();
        Map<String, Map<String, Object>> byPref = new LinkedHashMap<>();
        for (Object areaObj : Json.arr(event.get("areaScales"))) {
            Map<String, Object> area = Json.obj(areaObj);
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("pref", Json.str(area.get("pref")));
            converted.put("name", Json.str(area.get("name")));
            converted.put("scale", (long) Json.dbl(area.get("scale"), -1));
            // 📳 予想震度の上限 (99 は「〜程度以上」)。上限の概念が無い
            // 経路では null のため、その場合は付けない
            if (area.get("scaleTo") instanceof Number) {
                converted.put("scaleTo", (long) ((Number) area.get("scaleTo")).doubleValue());
            }
            areas.add(converted);
            byPref.put(Json.str(area.get("pref")), converted);
        }
        for (Object arrivalObj : Json.arr(event.get("areaArrivals"))) {
            Map<String, Object> arrival = Json.obj(arrivalObj);
            String pref = Json.str(arrival.get("pref"));
            String arrivalTime = Json.str(arrival.get("arrivalTime"));
            if (pref.isEmpty() || arrivalTime.isEmpty()) continue;
            Map<String, Object> converted = byPref.get(pref);
            if (converted == null) {
                converted = new LinkedHashMap<>();
                converted.put("pref", pref);
                converted.put("name", "");
                areas.add(converted);
                byPref.put(pref, converted);
            }
            converted.put("arrivalTime", arrivalTime);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("time", Json.str(event.get("time"))); // 発表時刻
        item.put("isCancel", Json.bool(event.get("cancel")));
        item.put("isWarn", Json.bool(event.get("isWarn")));
        item.put("isFinal", Json.bool(event.get("isFinal"))); // 最終報か
        item.put("isAssumption", Json.bool(event.get("isAssumption")));
        long serial = (long) Json.dbl(event.get("serial"), -1);
        if (serial > 0) item.put("serial", serial);
        String eventId = Json.str(event.get("eventId"));
        if (!eventId.isEmpty()) item.put("eventId", eventId);
        item.put("earthquake", earthquake);
        item.put("areas", areas);
        return item;
    }

    // 🌊 津波 → 552 相当の item 形式。通知 ID は電文 ID (key の
    // "552:" を外したもの) 由来とする
    static Map<String, Object> toTsunamiItem(Map<String, Object> event) {
        List<Object> areas = new ArrayList<>();
        for (Object areaObj : Json.arr(event.get("areas"))) {
            Map<String, Object> area = Json.obj(areaObj);
            Map<String, Object> converted = new LinkedHashMap<>();
            converted.put("name", Json.str(area.get("name")));
            converted.put("grade", Json.str(area.get("grade")));
            String heightLabel = Json.str(area.get("maxHeightLabel"));
            // 🌊 予想高さの数値 (m)。ラベルが無く数値だけある場合は
            // 数値からラベルを補う (表示側は description を使うため)
            boolean hasValue = area.get("maxHeightValue") instanceof Number;
            if (hasValue && heightLabel.isEmpty()) {
                double value = ((Number) area.get("maxHeightValue")).doubleValue();
                heightLabel = (value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value)) + "m";
            }
            if (!heightLabel.isEmpty() || hasValue) {
                Map<String, Object> maxHeight = new LinkedHashMap<>();
                if (!heightLabel.isEmpty()) maxHeight.put("description", heightLabel);
                if (hasValue) maxHeight.put("value", ((Number) area.get("maxHeightValue")).doubleValue());
                converted.put("maxHeight", maxHeight);
            }
            String arrival = Json.str(area.get("arrivalLabel"));
            String condition = Json.str(area.get("firstHeightCondition"));
            if (!arrival.isEmpty() || !condition.isEmpty()) {
                Map<String, Object> firstHeight = new LinkedHashMap<>();
                if (!arrival.isEmpty()) firstHeight.put("arrivalTime", arrival); // "HH:MM" 表記 (表示側でそのまま使う)
                if (!condition.isEmpty()) firstHeight.put("condition", condition);
                converted.put("firstHeight", firstHeight);
            }
            areas.add(converted);
        }
        String key = Json.str(event.get("key"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", key.startsWith("552:") ? key.substring(4) : key);
        item.put("time", Json.str(event.get("time")));
        item.put("cancelled", Json.bool(event.get("cancelled")));
        item.put("areas", areas);
        return item;
    }

    // ----------------------------------------
    // 💾 イベントストア (受信イベントの保持と重複判定)
    // ----------------------------------------
    // 重複判定は key + updatedAt (EEW の同一性は baseKey)。受理した
    // イベントだけを保持し、表示・通知の取得経路はここから変換して
    // 取り出す。到達不能・切断中は保持分が残るだけで、鮮度判定は
    // 表示・通知側の規則 (EEW の表示窓など) が担う
    static final class EventStore {
        private final Map<String, Map<String, Object>> quakes = new LinkedHashMap<>();
        private final Map<String, String> quakeUpdatedAt = new HashMap<>();
        private final Map<String, Map<String, Object>> eews = new LinkedHashMap<>();
        private final Map<String, String> eewUpdatedAt = new HashMap<>();
        private final Map<String, Map<String, Object>> tsunamis = new LinkedHashMap<>();
        private final Map<String, String> tsunamiUpdatedAt = new HashMap<>();
        private final Map<String, Map<String, Object>> warnings = new LinkedHashMap<>();
        private final Map<String, String> warningUpdatedAt = new HashMap<>();
        private final Map<String, Map<String, Object>> typhoons = new LinkedHashMap<>();
        private final Map<String, String> typhoonUpdatedAt = new HashMap<>();

        // 📥 イベントを取り込む。新規、または updatedAt が変わった更新なら true
        synchronized boolean accept(Map<String, Object> event) {
            if (!isA2sEvent(event)) return false;
            String kind = Json.str(event.get("kind"));
            // ⚠️ 警報の解除イベント (released: true) は保持せず、保持
            // 中の区域を取り除く。取り除けた場合だけ「変化あり」とする
            // (解除イベントは SSE でのみ届き、REST の一覧には載らない)
            if ("warning".equals(kind) && Json.bool(event.get("released"))) {
                String identity = Json.str(event.get("key"));
                warningUpdatedAt.remove(identity);
                return warnings.remove(identity) != null;
            }
            Map<String, Map<String, Object>> map;
            Map<String, String> updated;
            String identity;
            int cap;
            switch (kind) {
                case "quake" -> {
                    map = quakes;
                    updated = quakeUpdatedAt;
                    identity = Json.str(event.get("key"));
                    cap = QUAKE_CACHE_MAX;
                }
                case "eew" -> {
                    map = eews;
                    updated = eewUpdatedAt;
                    identity = Json.str(event.get("baseKey"));
                    cap = EEW_CACHE_MAX;
                }
                case "warning" -> {
                    map = warnings;
                    updated = warningUpdatedAt;
                    identity = Json.str(event.get("key"));
                    cap = WARNING_CACHE_MAX;
                }
                case "typhoon" -> {
                    map = typhoons;
                    updated = typhoonUpdatedAt;
                    identity = Json.str(event.get("key"));
                    cap = TYPHOON_CACHE_MAX;
                }
                default -> {
                    map = tsunamis;
                    updated = tsunamiUpdatedAt;
                    identity = Json.str(event.get("key"));
                    cap = TSUNAMI_CACHE_MAX;
                }
            }
            String updatedAt = Json.str(event.get("updatedAt"));
            if (map.containsKey(identity) && updatedAt.equals(updated.get(identity))) return false;
            map.put(identity, event);
            updated.put(identity, updatedAt);
            // 💾 上限を超えたら捨てる。地震系は発生・発表時刻が最も
            // 古いもの、警報・台風は時刻形式が一定しないため取り込み
            // 順の古いものから
            while (map.size() > cap) {
                String oldestKey;
                if ("warning".equals(kind) || "typhoon".equals(kind)) {
                    oldestKey = map.keySet().iterator().next();
                } else {
                    oldestKey = null;
                    long oldestMs = Long.MAX_VALUE;
                    for (Map.Entry<String, Map<String, Object>> entry : map.entrySet()) {
                        long timeMs = Http.parseP2pTimeMs(Json.str(entry.getValue().get("time")));
                        if (timeMs < oldestMs) {
                            oldestMs = timeMs;
                            oldestKey = entry.getKey();
                        }
                    }
                    if (oldestKey == null) break;
                }
                map.remove(oldestKey);
                updated.remove(oldestKey);
            }
            return true;
        }

        // 🌏 保持中の地震イベントを発生時刻の新しい順に返す
        synchronized List<Map<String, Object>> quakeEvents() {
            List<Map<String, Object>> events = new ArrayList<>(quakes.values());
            events.sort((a, b) -> Long.compare(
                    Http.parseP2pTimeMs(Json.str(b.get("time"))),
                    Http.parseP2pTimeMs(Json.str(a.get("time")))));
            return events;
        }

        // 🚨 表示窓 (Eew.DISPLAY_MS) 以内の最新 EEW イベントを返す。それ以外は null
        synchronized Map<String, Object> latestEewEvent(long nowMs) {
            Map<String, Object> best = null;
            long bestMs = -1;
            for (Map<String, Object> event : eews.values()) {
                long timeMs = Http.parseP2pTimeMs(Json.str(event.get("time")));
                if (timeMs > bestMs) {
                    bestMs = timeMs;
                    best = event;
                }
            }
            if (best == null || bestMs < 0 || nowMs - bestMs > Eew.DISPLAY_MS) return null;
            return best;
        }

        // 🌊 最新の津波イベントを返す。無ければ null
        synchronized Map<String, Object> latestTsunamiEvent() {
            Map<String, Object> best = null;
            long bestMs = -1;
            for (Map<String, Object> event : tsunamis.values()) {
                long timeMs = Http.parseP2pTimeMs(Json.str(event.get("time")));
                if (timeMs > bestMs) {
                    bestMs = timeMs;
                    best = event;
                }
            }
            return best;
        }

        // ⚠️ 保持中の警報イベント (現在発表中の区域だけ) を返す
        synchronized List<Map<String, Object>> warningEvents() {
            return new ArrayList<>(warnings.values());
        }

        // 🌀 保持中の台風イベントを返す
        synchronized List<Map<String, Object>> typhoonEvents() {
            return new ArrayList<>(typhoons.values());
        }

        synchronized void clear() {
            quakes.clear();
            quakeUpdatedAt.clear();
            eews.clear();
            eewUpdatedAt.clear();
            tsunamis.clear();
            tsunamiUpdatedAt.clear();
            warnings.clear();
            warningUpdatedAt.clear();
            typhoons.clear();
            typhoonUpdatedAt.clear();
        }
    }

    // ----------------------------------------
    // 📥 保持イベントの取り出し (表示・通知の取得経路が使う)
    // ----------------------------------------
    static final EventStore STORE = new EventStore();

    // 🌏 A.A.S 由来の地震を 551 相当の item 形式で返す (発生時刻の
    // 新しい順)。未接続・未受信なら空リスト
    public static List<Object> quakeItems() {
        List<Object> items = new ArrayList<>();
        for (Map<String, Object> event : STORE.quakeEvents()) items.add(toQuakeItem(event));
        return items;
    }

    // 🚨 A.A.S 由来の最新 EEW を内部共通形式で返す。表示窓外・未受信なら null
    public static Map<String, Object> latestEewItem(long nowMs) {
        Map<String, Object> event = STORE.latestEewEvent(nowMs);
        return event == null ? null : toEewItem(event);
    }

    // 🌊 A.A.S 由来の最新津波を 552 相当の item 形式で返す。未受信なら空リスト
    public static List<Object> tsunamiItems() {
        Map<String, Object> event = STORE.latestTsunamiEvent();
        return event == null ? List.of() : List.of(toTsunamiItem(event));
    }

    // ⚠️ A.A.S 由来の警報イベント (現在発表中の区域) をそのまま返す。未受信なら空リスト
    public static List<Map<String, Object>> warningEvents() {
        return STORE.warningEvents();
    }

    // 🌀 A.A.S 由来の台風イベントをそのまま返す。未受信なら空リスト
    public static List<Map<String, Object>> typhoonEvents() {
        return STORE.typhoonEvents();
    }

    // 📍 地点に対応する警報イベントを、A.A.S が返す区域情報
    // (prefName / areaName) から引く。照合: 都道府県名が一致する区域
    // のうち、区域名が地点の地域区分名と一致するものを選ぶ。A.A.S の
    // 区域名は都道府県名つきの表記 (例「群馬県南部」) があるため、
    // 接頭辞を取り除いた一致と末尾一致も同点として扱う。どの区域か
    // 特定できなければ null (県内全域への拡大解釈はしない)
    static Map<String, Object> matchWarningEvent(List<Map<String, Object>> events, Models.Location loc) {
        if (events == null || loc == null) return null;
        String pref = Geo.prefName(loc.prefCode());
        String area = loc.areaName() == null ? "" : loc.areaName().trim();
        Map<String, Object> best = null;
        int bestScore = 0;
        int prefCount = 0;
        Map<String, Object> onlyPrefMatch = null;
        for (Map<String, Object> event : events) {
            if (Json.bool(event.get("released"))) continue;
            boolean prefMatch = !pref.isEmpty() && pref.equals(Json.str(event.get("prefName")));
            // 📍 都道府県名が引けない地点 (prefCode 不明) は官署コードで照合する
            if (!prefMatch && !loc.prefCode().isEmpty()
                    && loc.prefCode().equals(Json.str(event.get("officeCode")))) prefMatch = true;
            if (!prefMatch) continue;
            prefCount++;
            onlyPrefMatch = event;
            String eventArea = Json.str(event.get("areaName")).trim();
            int score = 0;
            if (!area.isEmpty() && !eventArea.isEmpty()) {
                String stripped = eventArea.startsWith(pref) ? eventArea.substring(pref.length()) : eventArea;
                if (eventArea.equals(area) || stripped.equals(area)) score = 3;
                else if (eventArea.endsWith(area) || area.endsWith(eventArea)) score = 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = event;
            }
        }
        if (best != null) return best;
        // 📍 地域区分名が無い地点は、都道府県内の区域が1件だけならそれを採る
        if (area.isEmpty() && prefCount == 1) return onlyPrefMatch;
        return null;
    }

    // ⚠️ 地点に対応する警報イベントをストアから引く。無ければ null
    public static Map<String, Object> warningEventForLocation(Models.Location loc) {
        return matchWarningEvent(STORE.warningEvents(), loc);
    }

    static void clearStore() {
        STORE.clear();
    }

    // ----------------------------------------
    // 🌐 REST 取得 (GET /v1/events / GET /v1/status)
    // ----------------------------------------
    // 履歴参照 (地震一覧・津波・EEW のポーリング表示と通知チェック) は
    // SSE のストアだけに頼らず、REST で直近イベントを取り込んでから
    // 使う。ポーリング間隔は A.A.S の作法 (10秒以上) を守るため、
    // 種別ごとに最小間隔を空けて実リクエストする (それ以外はストア
    // で答える)

    // 🧪 イベント一覧の URL を組み立てる (テスト対象)
    static String eventsUrl(String base, String kind, int limit) {
        String trimmed = base == null ? "" : base;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        StringBuilder sb = new StringBuilder(trimmed).append("/v1/events?limit=")
                .append(limit > 0 ? limit : 50);
        if (kind != null && !kind.isEmpty()) sb.append("&kind=").append(kind);
        return sb.toString();
    }

    // 🧪 REST 応答 ({events: [...]}) から検証済みイベントだけを取り出す (テスト対象)
    static List<Map<String, Object>> parseEventsResponse(Object data) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object element : Json.arr(Json.obj(data).get("events"))) {
            Map<String, Object> event = Json.obj(element);
            if (isA2sEvent(event)) events.add(event);
        }
        return events;
    }

    // 🌐 認証つきで JSON を取得する (Authorization ヘッダを使う。
    // クエリ方式はログに残りやすいため使わない)
    static Object fetchJsonAuthorized(String url, String authToken) throws Exception {
        HttpClient client = ProxySupport.newClient(Duration.ofSeconds(15));
        return Http.withRetry(() -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "AEGIS-Windows/" + Models.VERSION)
                    .header("Accept", "application/json")
                    .GET();
            if (authToken != null && !authToken.isEmpty()) builder.header("Authorization", "Bearer " + authToken);
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new Http.HttpStatusException(response.statusCode());
            return Json.parse(response.body());
        }, Http.RETRY_DELAYS_MS);
    }

    // 🌐 REST で直近イベントを取得し、ストアへ取り込む。受理件数を返す
    static int fetchEvents(String kind, int limit) throws Exception {
        Object data = fetchJsonAuthorized(eventsUrl(baseUrl(), kind, limit), token());
        int accepted = 0;
        for (Map<String, Object> event : parseEventsResponse(data)) {
            if (STORE.accept(event)) accepted++;
        }
        return accepted;
    }

    private static final Map<String, Long> lastRefreshMs = new HashMap<>();
    static final long REST_REFRESH_MIN_MS = 10_000L;

    // 🌐 種別ごとの最小間隔を守って REST 回収する。未設定・失敗時は
    // 黙ってストアの保持分で続行する (呼び出し側や他機能を落とさない)
    static void refreshFromEvents(String kind, int limit) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        synchronized (lastRefreshMs) {
            Long last = lastRefreshMs.get(kind);
            if (last != null && now - last < REST_REFRESH_MIN_MS) return;
            lastRefreshMs.put(kind, now);
        }
        try {
            fetchEvents(kind, limit);
        } catch (Exception ignored) {}
    }

    // 🔍 接続診断用: GET /v1/status が認証つきで成功するか
    static boolean fetchStatusOk() {
        try {
            Object data = fetchJsonAuthorized(baseUrl() + "/v1/status", token());
            return Json.bool(Json.obj(data).get("ok"));
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------
    // 📡 SSE フレーム解析 (テスト可能な純粋な状態機械)
    // ----------------------------------------
    static final class SseParser {
        record Frame(String type, String data) {}

        private String eventType = "";
        private final StringBuilder data = new StringBuilder();
        private boolean hasData;

        // 📥 1行を取り込む。空行でフレームが完成したらそれを返す
        Frame feed(String line) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) {
                if (!hasData) {
                    eventType = "";
                    return null;
                }
                Frame frame = new Frame(eventType.isEmpty() ? "message" : eventType, data.toString());
                eventType = "";
                data.setLength(0);
                hasData = false;
                return frame;
            }
            if (line.startsWith(":")) return null; // heartbeat 等のコメント行
            if (line.startsWith("event:")) {
                eventType = line.substring(6).trim();
                return null;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (hasData) data.append("\n");
                data.append(value);
                hasData = true;
                return null;
            }
            return null; // id: / retry: 等は使わない
        }
    }

    // ----------------------------------------
    // 📡 SSE クライアント
    // ----------------------------------------
    // 🧪 接続の抽象 (テストで実接続しないフェイクに差し替えるため)。
    // 接続できたら本文を行ストリームで返し、切断・終了はストリームの
    // 終端または例外で知らせる
    public interface StreamConnector {
        java.util.stream.Stream<String> connect(String url, String token) throws Exception;
    }

    // 📡 java.net.http による既定の接続実装。認証は Authorization
    // ヘッダを使う。プロキシは Http と同じく環境変数から解決する
    static StreamConnector defaultConnector() {
        HttpClient client = ProxySupport.newClient(Duration.ofSeconds(15));
        return (url, authToken) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "AEGIS-Windows/" + Models.VERSION)
                    .header("Accept", "text/event-stream")
                    .GET();
            if (authToken != null && !authToken.isEmpty()) builder.header("Authorization", "Bearer " + authToken);
            HttpResponse<java.util.stream.Stream<String>> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) throw new Http.HttpStatusException(response.statusCode());
            return response.body();
        };
    }

    // 📡 A.A.S の SSE ストリームへ常時接続する。切断時は指数バック
    // オフで再接続し、再接続時の再生で切断中のイベントを回収する
    // (再生分はストアへマージするだけで liveHandler へは流さない)
    public static final class StreamClient {
        private final StreamConnector connector;
        private final String streamUrl;
        private final String token;
        private final EventStore store;
        private final Consumer<Map<String, Object>> liveHandler;
        private final long[] delaysMs;
        private final AtomicBoolean stopped = new AtomicBoolean(true);
        private Thread thread;
        private volatile java.util.stream.Stream<String> currentStream;
        private int failures;

        public StreamClient(StreamConnector connector, String baseUrl, String token, EventStore store,
                            Consumer<Map<String, Object>> liveHandler, long[] delaysMs) {
            this.connector = connector;
            this.streamUrl = baseUrl + "/v1/stream";
            this.token = token;
            this.store = store;
            this.liveHandler = liveHandler;
            this.delaysMs = delaysMs.length > 0 ? delaysMs : SSE_RECONNECT_DELAYS_MS;
        }

        public synchronized void start() {
            if (!stopped.get()) return;
            stopped.set(false);
            thread = new Thread(this::runLoop, "aegis-a2s-sse");
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            stopped.set(true);
            java.util.stream.Stream<String> stream = currentStream;
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {}
            }
            Thread current = thread;
            if (current != null) current.interrupt();
        }

        boolean isStopped() {
            return stopped.get();
        }

        private void runLoop() {
            while (!stopped.get()) {
                try {
                    java.util.stream.Stream<String> stream = connector.connect(streamUrl, token);
                    currentStream = stream;
                    failures = 0;
                    readStream(stream);
                } catch (Exception e) {
                    // ⚠️ 接続失敗・切断はここで握り、再接続へ倒す (監視ループは落とさない)
                } finally {
                    currentStream = null;
                }
                if (stopped.get()) break;
                long delay = delaysMs[Math.min(failures, delaysMs.length - 1)];
                failures++;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 📖 ストリームを読み、フレームごとにイベントへ変換して取り
        // 込む。ready が届くまでは接続時の再生分として扱う
        private void readStream(java.util.stream.Stream<String> stream) {
            SseParser parser = new SseParser();
            boolean replay = true;
            try (stream) {
                java.util.Iterator<String> lines = stream.iterator();
                while (!stopped.get() && lines.hasNext()) {
                    SseParser.Frame frame = parser.feed(lines.next());
                    if (frame == null) continue;
                    if (frame.type().equals("ready")) {
                        replay = false;
                        continue;
                    }
                    if (!frame.type().equals("quake") && !frame.type().equals("eew") && !frame.type().equals("tsunami")
                            && !frame.type().equals("warning") && !frame.type().equals("typhoon")) continue;
                    Object parsed;
                    try {
                        parsed = Json.parse(frame.data());
                    } catch (Exception e) {
                        continue;
                    }
                    Map<String, Object> event = Json.obj(parsed);
                    if (!isA2sEvent(event)) continue;
                    boolean changed = store.accept(event);
                    // ⚠️ 再生ガード: 再生分は状態へマージするだけで新規
                    // イベントとして扱わない。liveHandler へ流すのは
                    // ready 後の新規・更新だけ
                    if (!replay && changed && liveHandler != null) {
                        try {
                            liveHandler.accept(event);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    // ----------------------------------------
    // 📝 SSE の開始・停止 (Main から呼ぶ)
    // ----------------------------------------
    private static StreamClient streamClient;

    // 📡 新規・更新イベントが受理されたときだけ listener が呼ばれる
    // (重複排除・再生ガード済み)。無効 (URL・トークン未指定) なら
    // 何もしない。既に開始済みなら何もしないため、設定変更時は
    // stopStream してから呼ぶ
    public static synchronized void startStream(Consumer<Map<String, Object>> listener) {
        if (!enabled() || streamClient != null) return;
        streamClient = new StreamClient(defaultConnector(), baseUrl(), token(), STORE, listener, SSE_RECONNECT_DELAYS_MS);
        streamClient.start();
    }

    public static synchronized void stopStream() {
        if (streamClient != null) {
            streamClient.stop();
            streamClient = null;
        }
    }

    public static synchronized boolean streamRunning() {
        return streamClient != null;
    }
}
