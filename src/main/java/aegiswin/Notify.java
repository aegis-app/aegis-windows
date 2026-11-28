package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

// ========================================
// 🔔 Notify (通知: 新規イベント判定・重複排除)
// ========================================
// 📌 地震・津波・EEW・警報はすべて A.A.S のみから候補を取り、
// 未通知のものだけを通知対象にする。通知 ID は地震・津波は電文
// ID、EEW は発生時刻ベース、警報は地点名＋対象コード群のハッシュ。
public final class Notify {
    private Notify() {}

    // 🕐 発生から1時間超は通知しない (フラッド防止)
    public static final long FRESH_MS = 60 * 60 * 1000L;
    // 💾 通知済み ID の保持上限
    public static final int SEEN_MAX = 200;

    // 🔔 通知してよい新しさか (未来側は時計ずれを許容して5分まで)
    public static boolean isFresh(long occurredMs, long nowMs) {
        return occurredMs > 0 && nowMs - occurredMs <= FRESH_MS && occurredMs - nowMs <= 5 * 60 * 1000L;
    }

    // ----------------------------------------
    // 🌏 地震
    // ----------------------------------------
    // 🌏 551 相当の item から通知用イベントを取り出す (震度しきい値で絞る)
    public static List<Models.NotifyEvent> parseQuakeEvents(Object root, int minScale) {
        List<Models.NotifyEvent> events = new ArrayList<>();
        for (Object itemObj : Json.arr(root)) {
            Map<String, Object> item = Json.obj(itemObj);
            Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
            if (earthquake.isEmpty()) continue;
            int maxScale = (int) Json.dbl(earthquake.get("maxScale"), -1);
            if (maxScale < minScale) continue;
            Map<String, Object> hypocenter = Json.obj(earthquake.get("hypocenter"));
            String time = Json.str(earthquake.get("time"));
            String id = Json.str(item.get("id"));
            if (id.isEmpty()) id = time + "/" + Json.str(hypocenter.get("name"));
            double magnitude = Json.dbl(hypocenter.get("magnitude"), -1);
            String title = "🌏 地震： 震度" + Quakes.scaleToStr(maxScale) + " " + Json.str(hypocenter.get("name"), "不明");
            String body = (time.length() >= 16 ? time.substring(5, 16) : time) + "発生 / "
                    + (magnitude < 0 ? "M不明" : "M" + magnitude);
            events.add(new Models.NotifyEvent("quake:" + id, "quake", title, body, Http.parseP2pTimeMs(time)));
        }
        return events;
    }

    // ----------------------------------------
    // 🌊 津波
    // ----------------------------------------
    // 🔔 552 相当の最新報から通知用イベントを作る。解除済み・対象なしなら null
    public static Models.NotifyEvent parseTsunamiEvent(Object root) {
        List<Object> data = Json.arr(root);
        if (data.isEmpty()) return null;
        Map<String, Object> item = Json.obj(data.get(0));
        if (Json.bool(item.get("cancelled"))) return null;
        if (Json.arr(item.get("areas")).isEmpty()) return null;
        Models.Section section = Quakes.parseTsunami(root);
        String id = Json.str(item.get("id"));
        if (id.isEmpty()) id = Json.str(item.get("time"));
        return new Models.NotifyEvent("tsunami:" + id, "tsunami", "🌊 津波情報", section.body(),
                Http.parseP2pTimeMs(Json.str(item.get("time"))));
    }

    // ----------------------------------------
    // 🚨 EEW
    // ----------------------------------------
    // 🚨 EEW の通知 ID: レコード ID ではなく発生時刻 (originTime) で
    // 固定する。EEW は続報のたびに新しいレコードとして配信され
    // レコード ID が変わるため、レコード ID のままだと同じ地震で
    // 何度も再通知されてしまう
    static String eewId(Map<String, Object> item) {
        Map<String, Object> earthquake = Json.obj(item.get("earthquake"));
        String origin = Json.str(earthquake.get("originTime"));
        if (origin.isEmpty()) origin = Json.str(earthquake.get("time"));
        if (origin.isEmpty()) origin = Json.str(item.get("time"));
        return "eew:" + origin;
    }

    // 🔔 新鮮な EEW 最新報から通知用イベントを作る。取り消し・古い
    // 場合は null。通知 ID には推定最大震度を付け (例
    // "eew:<発生時刻>#45")、重複排除側で「通知済みの最大震度より
    // 上がった続報だけ再通知する」判定に使う。通知タイトルにも
    // 予報/警報の区別を出す
    public static Models.NotifyEvent parseEewEvent(Object root, long nowMs) {
        Map<String, Object> item = Eew.freshItem(root, nowMs);
        if (item == null || Eew.cancelled(item)) return null;
        Models.Section section = Eew.sectionFromItem(item, nowMs);
        int est = Eew.estScale(item);
        String title = "🚨 緊急地震速報" + (Eew.isWarn(item) ? "（警報）" : "（予報）");
        return new Models.NotifyEvent(eewId(item) + "#" + est, "eew", title, section.body(),
                Http.parseP2pTimeMs(Json.str(item.get("time"))));
    }

    // ----------------------------------------
    // ⚠️ 警報・注意報
    // ----------------------------------------
    // ⚠️ 警報コードの重要度: 3=特別警報, 2=警報, 1=注意報
    static int warningRank(String code) {
        return switch (code) {
            case "32", "33", "35", "36", "37", "38" -> 3;
            case "02", "03", "04", "05", "06", "07", "08" -> 2;
            default -> 1;
        };
    }

    // ⚠️ A.A.S 形式の重要度 (特別警報=4 / 警報=3 / 注意報=2) を
    // エントリから取る。重要度が付いていない場合はコードから従来
    // の順位をこの尺度へ読み替える
    static int warningSeverityOf(Models.WarningEntry entry) {
        if (entry.severity() > 0) return entry.severity();
        return switch (warningRank(entry.code())) {
            case 3 -> 4;
            case 2 -> 3;
            default -> 2;
        };
    }

    // 🔔 警報の通知 ID: 「地点名 + 通知対象の警報コード群のハッシュ」で作る。
    // - 発表時刻 (reportDatetime) は含めない: 県の警報文書は他地域
    //   の更新でも発表時刻が進むため、含めると自地点に無関係な更新
    //   で再通知されてしまう。対象警報の顔ぶれ (コード群) が変わっ
    //   たときだけ新しい ID になる
    // - 地点名を含める: 都道府県コードだけだと、同一都道府県の複数
    //   地点で ID が衝突して片方の通知が握り潰されてしまうため
    static String warningId(String locName, List<String> codes) {
        List<String> sorted = new ArrayList<>(codes);
        java.util.Collections.sort(sorted);
        return "warning:" + locName + ":" + Integer.toHexString(String.join(",", sorted).hashCode());
    }

    // 🔔 地点の A.A.S 警報イベントから通知用イベントを作る。対象
    // 区域の照合は A2s.warningEventForLocation で済んでいる前提。
    // 条件: 特別警報・警報は常時、注意報は includeAdvisory のとき
    // だけ。対象が無ければ null
    public static Models.NotifyEvent warningEvent(Models.Location loc, Map<String, Object> warnEvent,
                                                  boolean includeAdvisory) {
        List<Models.WarningEntry> entries = Weather.warningEntriesFromA2s(warnEvent);
        List<String> names = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        int bestSeverity = 0;
        for (Models.WarningEntry entry : entries) {
            int severity = warningSeverityOf(entry);
            if (severity < 3 && !includeAdvisory) continue;
            bestSeverity = Math.max(bestSeverity, severity);
            if (!names.contains(entry.name())) names.add(entry.name());
            if (!codes.contains(entry.code())) codes.add(entry.code());
        }
        if (names.isEmpty()) return null;
        String reportDatetime = Json.str(warnEvent.get("reportDatetime"));
        String title = (bestSeverity >= 4 ? "🔴 特別警報" : "⚠ 警報・注意報") + "（" + loc.name() + "）";
        String body = String.join("、", names)
                + (reportDatetime.length() >= 16
                ? "（" + reportDatetime.substring(5, 10).replace('-', '/') + " " + reportDatetime.substring(11, 16) + "発表）" : "");
        return new Models.NotifyEvent(warningId(loc.name(), codes), "warning", title, body,
                Http.parseJmaTimeMs(reportDatetime));
    }

    // ----------------------------------------
    // 📥 候補の取得と重複排除
    // ----------------------------------------
    // 🌦️ 通知候補をまとめて取得する。地震・津波・EEW・警報はすべて
    // A.A.S のみ (完全一本化、履歴は REST /v1/events で回収)。
    // 個別の取得失敗はそのソースを黙って飛ばす。新しさの判定は
    // 呼び出し側 (isFresh) で行う
    public static List<Models.NotifyEvent> fetchEvents(Models.NotifyPrefs prefs) {
        List<Models.NotifyEvent> events = new ArrayList<>();
        Future<List<Models.NotifyEvent>> quakeFuture = Http.FETCH_POOL.submit(() -> {
            A2s.refreshFromEvents("quake", 50);
            return parseQuakeEvents(A2s.quakeItems(), prefs.quakeMinScale());
        });
        Future<Models.NotifyEvent> tsunamiFuture = Http.FETCH_POOL.submit(() -> {
            A2s.refreshFromEvents("tsunami", 5);
            return parseTsunamiEvent(A2s.tsunamiItems());
        });
        Future<Models.NotifyEvent> eewFuture = Http.FETCH_POOL.submit(() -> {
            long now = System.currentTimeMillis();
            Map<String, Object> item = Eew.currentItem(now);
            return item == null ? null : parseEewEvent(List.of(item), now);
        });
        List<Future<Models.NotifyEvent>> warningFutures = new ArrayList<>();
        for (Models.Location loc : Geo.effectiveLocations()) {
            final Models.Location target = loc;
            warningFutures.add(Http.FETCH_POOL.submit(() -> {
                // ⚠️ 警報は REST で有効集合を回収し、地点の区域を引く
                // (未設定・到達不能時はストアの保持分だけで判定し、例外にしない)
                A2s.refreshFromEvents("warning", A2s.WARNING_CACHE_MAX);
                return warningEvent(target, A2s.warningEventForLocation(target), prefs.warningIncludeAdvisory());
            }));
        }
        try {
            events.addAll(quakeFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception ignored) {}
        try {
            Models.NotifyEvent tsunami = tsunamiFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (tsunami != null) events.add(tsunami);
        } catch (Exception ignored) {}
        try {
            Models.NotifyEvent eew = eewFuture.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (eew != null) events.add(eew);
        } catch (Exception ignored) {}
        for (Future<Models.NotifyEvent> future : warningFutures) {
            try {
                Models.NotifyEvent warning = future.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                if (warning != null) events.add(warning);
            } catch (Exception ignored) {}
        }
        return events;
    }

    // 🔔 まだ通知していないイベントだけを返し、通知対象になった
    // イベントの ID を seen に登録する (重複排除)。
    // EEW は例外: ID 末尾の推定最大震度を比べ、通知済みの最大震度
    // より上がった続報だけを再通知する (下がった・同じ続報は登録
    // もしない)。旧形式 ID (震度なし "eew:<発生時刻>") が seen に
    // 残っている場合は通知済みとして扱い、更新直後の二重通知を防ぐ
    public static List<Models.NotifyEvent> filterNew(List<Models.NotifyEvent> events, java.util.Set<String> seen) {
        List<Models.NotifyEvent> fresh = new ArrayList<>();
        for (Models.NotifyEvent event : events) {
            if ("eew".equals(event.kind()) && event.id().contains("#")) {
                String base = event.id().substring(0, event.id().lastIndexOf('#'));
                int scale;
                try {
                    scale = Integer.parseInt(event.id().substring(event.id().lastIndexOf('#') + 1));
                } catch (Exception e) {
                    scale = -1;
                }
                if (seen.contains(base)) continue; // 旧形式で通知済み
                int maxSeen = Integer.MIN_VALUE;
                boolean anySeen = false;
                for (String seenId : seen) {
                    if (!seenId.startsWith(base + "#")) continue;
                    try {
                        maxSeen = Math.max(maxSeen, Integer.parseInt(seenId.substring(base.length() + 1)));
                        anySeen = true;
                    } catch (Exception ignored) {}
                }
                if (anySeen && scale <= maxSeen) continue; // 上方修正でない続報は再通知しない
                seen.add(event.id());
                fresh.add(event);
                continue;
            }
            if (seen.add(event.id())) fresh.add(event);
        }
        return fresh;
    }

    // ----------------------------------------
    // 💾 通知済み ID の永続化
    // ----------------------------------------
    // 🔔 永続化形式 (改行区切り) との相互変換。復元時は上限を超えた古い分を捨てる
    public static LinkedHashSet<String> parseSeenIds(String raw) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String line : raw.split("\n")) {
                String id = line.trim();
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return pruneSeenIds(ids);
    }

    public static LinkedHashSet<String> pruneSeenIds(LinkedHashSet<String> ids) {
        while (ids.size() > SEEN_MAX) {
            var iterator = ids.iterator();
            iterator.next();
            iterator.remove();
        }
        return ids;
    }

    public static String joinSeenIds(java.util.Collection<String> ids) {
        return String.join("\n", pruneSeenIds(new LinkedHashSet<>(ids)));
    }
}
