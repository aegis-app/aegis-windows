package aegiswin;

import java.util.ArrayList;
import java.util.List;

// ========================================
// 📍 Geo (地理計算・地点)
// ========================================
// 📌 球面距離、地図タイル座標、地点の検証と市区町村照合。
// すべて純粋関数で、画面にも通信にも依存しない。
public final class Geo {
    private Geo() {}

    // ----------------------------------------
    // 🌐 球面距離
    // ----------------------------------------
    // 📝 2点間の球面距離 (km)
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double radiusKm = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * radiusKm * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    // ----------------------------------------
    // 🗺️ 地図タイル (標準 XYZ / slippy map)
    // ----------------------------------------
    // 📝 小数タイル座標 (切り捨て前)。マーカー位置計算に使う
    public static double tileXFrac(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom);
    }

    public static double tileYFrac(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * (1 << zoom);
    }

    // 📝 整数タイル座標 (範囲内に丸める)
    public static int[] tileXY(double lat, double lon, int zoom) {
        int count = 1 << zoom;
        int x = Math.max(0, Math.min(count - 1, (int) Math.floor(tileXFrac(lon, zoom))));
        int y = Math.max(0, Math.min(count - 1, (int) Math.floor(tileYFrac(lat, zoom))));
        return new int[]{x, y};
    }

    // 📍 地点のキャンバス上のピクセル位置。小数タイル座標から算出する
    // ため、タイル中心固定 (最大で半タイルずれる) と違い実位置に描ける。
    // originX / originY はキャンバス左上に対応するタイル座標
    public static int[] markerPixel(double lat, double lon, int zoom, int originX, int originY) {
        int px = (int) Math.round((tileXFrac(lon, zoom) - originX) * 256.0);
        int py = (int) Math.round((tileYFrac(lat, zoom) - originY) * 256.0);
        return new int[]{px, py};
    }

    // ----------------------------------------
    // 📍 地点
    // ----------------------------------------
    public static String prefName(String prefCode) {
        for (String[] pref : Models.PREFECTURES) {
            if (pref[1].equals(prefCode)) return pref[0];
        }
        return "";
    }

    // 📝 監視対象の地点一覧 (既定地点 + ユーザー追加地点)
    public static Models.Location[] effectiveLocations() {
        List<Models.Location> all = new ArrayList<>(List.of(Models.LOCATIONS));
        all.addAll(Models.customLocations);
        return all.toArray(new Models.Location[0]);
    }

    // 📍 地点名に対応する市区町村名 (観測点の addr との照合用)。
    // 地点名そのものが市区町村でない既定地点 (高崎・大宮) は実際の
    // 市区町村名に読み替える
    public static String cityOf(Models.Location loc) {
        return switch (loc.name()) {
            case "高崎" -> "高崎市";
            case "大宮" -> "さいたま市";
            default -> {
                String name = loc.name();
                yield (name.endsWith("市") || name.endsWith("区") || name.endsWith("町") || name.endsWith("村"))
                        ? name : name + "市";
            }
        };
    }

    // 📍 観測点の addr (市区町村) が地点の市区町村と一致するか
    // (政令市の「さいたま市大宮区」のような区併記は前方一致で拾う)
    public static boolean addrMatchesCity(String addr, Models.Location loc) {
        if (addr == null || addr.isEmpty()) return false;
        String city = cityOf(loc);
        return addr.equals(city) || addr.startsWith(city);
    }

    // ----------------------------------------
    // ✅ カスタム地点の座標検証
    // ----------------------------------------
    // 📝 都道府県庁所在地の代表座標 (Models.PREFECTURES と同順)。
    // 座標と都道府県の簡易照合に使う
    static final double[][] PREF_CENTERS = {
            {43.06, 141.35}, {40.82, 140.74}, {39.70, 141.15}, {38.27, 140.87},
            {39.72, 140.10}, {38.24, 140.34}, {37.75, 140.47}, {36.34, 140.45},
            {36.57, 139.88}, {36.39, 139.06}, {35.86, 139.65}, {35.61, 140.12},
            {35.69, 139.69}, {35.45, 139.64}, {37.90, 139.06}, {36.70, 137.21},
            {36.59, 136.63}, {36.07, 136.22}, {35.66, 138.57}, {36.65, 138.18},
            {35.39, 136.72}, {34.98, 138.38}, {35.18, 136.91}, {34.73, 136.51},
            {35.00, 135.87}, {35.02, 135.76}, {34.69, 135.50}, {34.69, 135.83},
            {34.69, 135.19}, {34.61, 135.80}, {34.58, 136.07}, {34.34, 134.04},
            {34.22, 134.02}, {33.42, 134.24}, {33.56, 133.53}, {33.25, 130.98},
            {33.84, 132.77}, {33.25, 131.67}, {32.91, 130.42}, {32.79, 130.74},
            {33.25, 130.30}, {32.75, 129.87}, {32.80, 130.71}, {33.24, 131.61},
            {31.91, 131.42}, {31.56, 130.56}, {26.21, 127.68}};

    // 📍 カスタム地点の座標検証。戻り値は警告文のリスト (空なら問題なし)。
    // 範囲外 (緯度±90・経度±180 超) は明らかな誤入力として呼び出し側で
    // 拒否し、日本域外と都道府県の食い違いは警告に留める
    public static List<String> validateLocation(double lat, double lon, String prefCode) {
        List<String> warnings = new ArrayList<>();
        if (Double.isNaN(lat) || Double.isNaN(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            warnings.add("緯度・経度が範囲外です（緯度 -90〜90、経度 -180〜180）");
            return warnings;
        }
        // ⚠️ 日本域 (おおよそ緯度20〜46・経度122〜154) の外は警告する
        if (lat < 20 || lat > 46 || lon < 122 || lon > 154) {
            warnings.add("座標が日本域外です。本当にこの地点でよいですか？");
        }
        // 📝 座標と都道府県の簡易照合: 選択した都道府県の代表座標から
        // 大きく離れていて、別の都道府県の代表座標のほうが近い場合は警告
        int selected = -1;
        for (int i = 0; i < Models.PREFECTURES.length; i++) {
            if (Models.PREFECTURES[i][1].equals(prefCode)) selected = i;
        }
        if (selected >= 0) {
            double distanceSelected = haversineKm(lat, lon, PREF_CENTERS[selected][0], PREF_CENTERS[selected][1]);
            int nearest = selected;
            double distanceNearest = distanceSelected;
            for (int i = 0; i < PREF_CENTERS.length; i++) {
                double distance = haversineKm(lat, lon, PREF_CENTERS[i][0], PREF_CENTERS[i][1]);
                if (distance < distanceNearest) {
                    distanceNearest = distance;
                    nearest = i;
                }
            }
            // 📍 県境付近の正当な地点 (軽井沢など) で誤警告しないよう、
            // 絶対距離だけでなく「選択中の都道府県より別の都道府県が
            // 明確に近い」場合に限る
            if (nearest != selected && distanceSelected > 60 && distanceSelected > 2 * distanceNearest + 20) {
                warnings.add("座標は " + Models.PREFECTURES[nearest][0] + " 付近です（選択中: "
                        + Models.PREFECTURES[selected][0] + "）。都道府県の選択を確認してください");
            }
        }
        return warnings;
    }
}
