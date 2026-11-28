package aegiswin;

import java.util.ArrayList;
import java.util.List;

// ========================================
// 🧩 Models (共通データ型・定数)
// ========================================
// 📌 画面・取得・通知の各層が共有するレコードと定数をここに集約する。
// レコードはすべて不変で、層をまたいでも安全に受け渡せる。
public final class Models {
    private Models() {}

    // 🏷️ バージョン (再構築で 1.0.0 に振り直し)
    public static final String VERSION = "1.0.0";

    // ----------------------------------------
    // 📍 地点
    // ----------------------------------------
    public record Location(String name, double lat, double lon, String prefCode, String areaName) {}

    // 🚨 47都道府県 (名称・気象庁予報コード)。EEW の対象地域照合と地点追加フォームで使う
    public static final String[][] PREFECTURES = {
            {"北海道", "016000"}, {"青森県", "020000"}, {"岩手県", "030000"}, {"宮城県", "040000"},
            {"秋田県", "050000"}, {"山形県", "060000"}, {"福島県", "070000"}, {"茨城県", "080000"},
            {"栃木県", "090000"}, {"群馬県", "100000"}, {"埼玉県", "110000"}, {"千葉県", "120000"},
            {"東京都", "130000"}, {"神奈川県", "140000"}, {"新潟県", "150000"}, {"富山県", "160000"},
            {"石川県", "170000"}, {"福井県", "180000"}, {"山梨県", "190000"}, {"長野県", "200000"},
            {"岐阜県", "210000"}, {"静岡県", "220000"}, {"愛知県", "230000"}, {"三重県", "240000"},
            {"滋賀県", "250000"}, {"京都府", "260000"}, {"大阪府", "270000"}, {"兵庫県", "280000"},
            {"奈良県", "290000"}, {"和歌山県", "300000"}, {"鳥取県", "310000"}, {"島根県", "320000"},
            {"岡山県", "330000"}, {"広島県", "340000"}, {"山口県", "350000"}, {"徳島県", "360000"},
            {"香川県", "370000"}, {"愛媛県", "380000"}, {"高知県", "390000"}, {"福岡県", "400000"},
            {"佐賀県", "410000"}, {"長崎県", "420000"}, {"熊本県", "430000"}, {"大分県", "440000"},
            {"宮崎県", "450000"}, {"鹿児島県", "460000"}, {"沖縄県", "471000"}};

    // 📍 既定の監視地点 (ユーザーの生活圏: 高崎・大宮)
    public static final Location TAKASAKI = new Location("高崎", 36.3219, 139.0023, "100000", "南部");
    public static final Location OMIYA = new Location("大宮", 35.9064, 139.6239, "110000", "南部");
    public static final Location[] LOCATIONS = {TAKASAKI, OMIYA};

    // ⚙️ ユーザーが追加した任意地点 (設定画面から Main が読み込んで設定する)
    public static volatile List<Location> customLocations = new ArrayList<>();

    // ----------------------------------------
    // 🖥️ 構造化データ (カード UI 用)
    // ----------------------------------------
    // 📝 level: info | ok | warn | danger
    public record Section(String title, String body, String level) {}

    public record Quake(String place, String detail, String sev, int maxScale, double lat, double lon, long epochMs) {
        public Quake(String place, String detail, String sev, int maxScale) {
            this(place, detail, sev, maxScale, 0, 0, 0);
        }

        public Quake(String place, String detail, String sev, int maxScale, double lat, double lon) {
            this(place, detail, sev, maxScale, lat, lon, 0);
        }
    }

    public record EewArrival(String locName, long arrivalMs, boolean estimated) {}

    public record EewInfo(Section section, List<EewArrival> arrivals) {}

    // ⚠️ severity は A.A.S 形式 (特別警報=4 / 警報=3 / 注意報=2、0 は不明)
    public record WarningEntry(String code, String name, int severity) {
        public WarningEntry(String code, String name) {
            this(code, name, 0);
        }
    }

    public record HourlyEntry(String dateLabel, int hour, String emoji, String weather, Double tempC, int popPct,
                              Double precipMm, int popIntervalMax) {}

    public record DailyEntry(String dateLabel, String emoji, String weather, Double minC, Double maxC, int popPct,
                             Double precipMm) {}

    public record WeatherTables(List<HourlyEntry> hourly, List<DailyEntry> weekly) {}

    public record RadarFrame(String path, long timeEpochSec) {}

    public record JmaNowcastFrame(String basetime, String validtime) {}

    public record NewsItem(String title, String summary, String link, String time) {}

    public record DiagResult(String name, String url, boolean ok, long ms, String note) {}

    // ----------------------------------------
    // 🔔 通知
    // ----------------------------------------
    // 📝 kind: eew | tsunami | quake | warning
    public record NotifyEvent(String id, String kind, String title, String body, long occurredMs) {}

    // 🌏 quakeMinScale は震度値コード (10=震度1, 20=2, 30=3, 40=4, 45=5弱)
    public record NotifyPrefs(int quakeMinScale, boolean warningIncludeAdvisory) {}
}
