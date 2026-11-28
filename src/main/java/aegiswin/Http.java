package aegiswin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// ========================================
// 🌐 Http (取得基盤: HTTP / リトライ / 時刻変換 / 接続診断)
// ========================================
// 📌 全ソースの取得はここを通す。リトライ方針・エラー文の日本語化・
// 時刻パースのタイムゾーン規則を一箇所に集約し、ソースごとの実装が
// ばらつかないようにする。
public final class Http {
    private Http() {}

    // 🌐 共有 HTTP クライアント
    private static final HttpClient CLIENT = ProxySupport.newClient(Duration.ofSeconds(15));

    // 🔁 リトライの待機列 (要素数がリトライ回数、試行回数は +1)
    public static final long[] RETRY_DELAYS_MS = {1000, 3000};

    // 🌦️ 並列取得用の共有プール (天気の複数要素や複数地点を同時に取る)。
    // スレッドは daemon にする: 非 daemon のままだと --cli 終了後も
    // プールのアイドルスレッド (既定60秒保持) が残り JVM が終了しない。
    // GUI は終了時に System.exit するため daemon 化しても影響しない
    public static final ExecutorService FETCH_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "aegis-fetch");
        thread.setDaemon(true);
        return thread;
    });

    // 🌐 表示側の待ち上限 (秒)。取得1回の最悪ケース (25秒タイムアウト
    // ×3回 + バックオフ 1秒+3秒 = 約79秒) を下回る上限にすると、取得
    // できているのに表示側が先に諦めて「取得できませんでした」になる
    // (姉妹実装で実際に起きた不具合と同じ構図)。上限は最悪ケースより
    // 十分大きく取る
    public static final long DISPLAY_WAIT_SECONDS = 120;

    // 🌐 HTTP ステータス由来の失敗。4xx か 5xx かでリトライ可否を
    // 判定するために使う。メッセージは "HTTP <status>" とし、
    // friendlyError の判定 (startsWith "HTTP ") と互換に保つ
    public static final class HttpStatusException extends IllegalStateException {
        public final int status;

        public HttpStatusException(int status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    // 🌐 取得の1回の試行を表す関数。リトライのコアをネットワーク
    // なしでテストできるよう、試行そのものを差し替え可能にしている
    public interface FetchAttempt<T> {
        T run() throws Exception;
    }

    // 🔁 リトライのコア: 全試行が失敗したら最後の例外を投げる。
    // 4xx は即失敗 (リトライしない)、5xx と通信エラーはリトライする
    public static <T> T withRetry(FetchAttempt<T> attempt, long[] delaysMs) throws Exception {
        Exception last = null;
        for (int i = 0; i <= delaysMs.length; i++) {
            try {
                return attempt.run();
            } catch (HttpStatusException e) {
                if (e.status < 500) throw e;
                last = e;
            } catch (Exception e) {
                last = e;
            }
            if (i < delaysMs.length) {
                try {
                    Thread.sleep(delaysMs[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("fetch failed");
    }

    // 🌐 1回だけ取得して本文バイト列を返す (200 以外は HttpStatusException)。
    // gzip で転送量も削減する
    private static byte[] fetchBytesOnce(String url, boolean json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "AEGIS-Windows/" + Models.VERSION)
                .header("Accept-Encoding", "gzip")
                .GET();
        if (json) builder.header("Accept", "application/json");
        HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) throw new HttpStatusException(response.statusCode());
        byte[] body = response.body();
        if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
            try (java.util.zip.GZIPInputStream gz =
                         new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                body = gz.readAllBytes();
            }
        }
        return body;
    }

    public static Object fetchJson(String url) throws Exception {
        return withRetry(
                () -> Json.parse(new String(fetchBytesOnce(url, true), java.nio.charset.StandardCharsets.UTF_8)),
                RETRY_DELAYS_MS);
    }

    // 🌐 テキスト取得 (RSS など JSON 以外用)。リトライ方針は fetchJson と同じ
    public static String fetchText(String url) throws Exception {
        return withRetry(
                () -> new String(fetchBytesOnce(url, false), java.nio.charset.StandardCharsets.UTF_8),
                RETRY_DELAYS_MS);
    }

    // 🌐 画像タイル等のバイナリ取得。タイルは1枚欠けても全体を表示する
    // ため、失敗時は null を返す
    public static byte[] fetchBytes(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "AEGIS-Windows/" + Models.VERSION)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // 🌐 サーバー疎通確認用 (生テキストで取得し、応答サイズを返す)
    public static int fetchTextLength(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "AEGIS-Windows/" + Models.VERSION)
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
        return response.body() == null ? 0 : response.body().length();
    }

    // ----------------------------------------
    // 🕐 時刻変換
    // ----------------------------------------
    // 🔄 P2P 形式の時刻 ("yyyy/MM/dd HH:mm:ss[.SSS]") を epoch ミリ秒に
    // 変換する。失敗時は -1。P2P の時刻は日本時間 (JST) のため、端末の
    // デフォルト TZ に依存しないよう Asia/Tokyo を明示する (JST 以外の
    // 環境で発生時刻・鮮度判定がずれるのを防ぐ)
    public static long parseP2pTimeMs(String value) {
        if (value == null || value.length() < 19) return -1;
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.JAPAN);
            format.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            java.util.Date parsed = format.parse(value.substring(0, 19));
            return parsed == null ? -1 : parsed.getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    // 🔄 ISO 8601 (オフセット付き) を epoch ミリ秒に変換する。失敗時は -1
    public static long parseJmaTimeMs(String value) {
        if (value == null || value.isEmpty()) return -1;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    // ----------------------------------------
    // 💬 エラーの日本語化
    // ----------------------------------------
    // 🔄 例外をユーザー向けの短い日本語に変換する (生の英語メッセージは出さない)
    public static String friendlyError(Exception e) {
        String message = String.valueOf(e.getMessage());
        if (e instanceof java.net.http.HttpTimeoutException || message.contains("timed out") || message.contains("timeout")) {
            return "通信がタイムアウトしました";
        }
        if (message.startsWith("HTTP ")) return "サーバーエラー（" + message + "）";
        if (message.contains("UnknownHost") || message.contains("unknown host")) return "サーバーに接続できませんでした";
        if (message.contains("abort") || message.contains("reset") || message.contains("closed")) return "通信が切断されました";
        // 🔌 プロキシに拒否された環境では、例外メッセージに denied 等がそのまま含まれることがある
        if (message.contains("denied") || message.contains("Deny")) return "ネットワークが許可されていません";
        return "通信エラーが発生しました";
    }

    // ----------------------------------------
    // 🔍 接続診断 (自己診断)
    // ----------------------------------------
    // 📝 各データソースへ実際にリクエストし、ソースごとの成否と所要
    // ミリ秒を返す (全ソースを FETCH_POOL で並列に試行する)
    public static List<Models.DiagResult> diagnose(String serverBase) {
        record Source(String name, String url, boolean text) {}
        List<Source> sources = new ArrayList<>(List.of(
                new Source("気象庁 予報", "https://www.jma.go.jp/bosai/forecast/data/forecast/100000.json", false),
                new Source("Open-Meteo", "https://api.open-meteo.com/v1/forecast?latitude=36.3219&longitude=139.0023&current=temperature_2m&forecast_days=1&timezone=Asia%2FTokyo&models=jma_seamless,ecmwf_ifs025", false),
                new Source("RainViewer（レーダー）", "https://api.rainviewer.com/public/weather-maps.json", false),
                new Source("気象庁 ナウキャスト（レーダー第2ソース）", "https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json", false),
                new Source("NHK RSS（ニュース）", "https://www3.nhk.or.jp/rss/news/cat0.xml", true)));
        String base = serverBase == null ? "" : serverBase.trim();
        if (!base.isEmpty()) {
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            sources.add(new Source("AEGISサーバー /api/briefing", base + "/api/briefing", false));
        }
        List<Future<Models.DiagResult>> futures = new ArrayList<>();
        // 📡 地震・津波・EEW・警報・台風は A.A.S のみ (完全一本化)。認証が
        // 必要なため、A.A.S が設定されている場合だけ専用経路で診断する
        if (A2s.enabled()) {
            futures.add(FETCH_POOL.submit(() -> {
                long started = System.nanoTime();
                boolean ok = A2s.fetchStatusOk();
                long ms = (System.nanoTime() - started) / 1_000_000;
                return new Models.DiagResult("A.A.S（地震・津波・EEW・警報・台風）", A2s.baseUrl() + "/v1/status", ok, ms,
                        ok ? "" : "A.A.S に接続できませんでした");
            }));
        }
        for (Source source : sources) {
            futures.add(FETCH_POOL.submit(() -> {
                long started = System.nanoTime();
                try {
                    if (source.text()) fetchText(source.url());
                    else fetchJson(source.url());
                    return new Models.DiagResult(source.name(), source.url(), true, (System.nanoTime() - started) / 1_000_000, "");
                } catch (Exception e) {
                    return new Models.DiagResult(source.name(), source.url(), false, (System.nanoTime() - started) / 1_000_000, friendlyError(e));
                }
            }));
        }
        List<Models.DiagResult> results = new ArrayList<>();
        for (Future<Models.DiagResult> future : futures) {
            try {
                results.add(future.get(DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(new Models.DiagResult("不明なソース", "", false, 0, friendlyError(e)));
            }
        }
        return results;
    }

    // 🔍 診断結果を一覧テキストに整形する
    public static String formatDiag(List<Models.DiagResult> results) {
        StringBuilder sb = new StringBuilder();
        for (Models.DiagResult result : results) {
            if (sb.length() > 0) sb.append("\n");
            if (result.ok()) sb.append("○ 接続OK（").append(result.ms()).append("ms）： ").append(result.name());
            else sb.append("× 失敗（").append(result.note()).append("）： ").append(result.name());
        }
        return sb.toString();
    }
}
