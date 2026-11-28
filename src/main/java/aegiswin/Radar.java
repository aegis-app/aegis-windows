package aegiswin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// ========================================
// 📡 Radar (気象レーダー: RainViewer / 地理院 / 気象庁ナウキャスト)
// ========================================
// 📌 RainViewer を主ソース、気象庁降水ナウキャストを第2ソース
// (z8 ネイティブ解像度の補完＋フォールバック) として併用する。
// ベース地図は国土地理院タイル。合成そのものは Main (描画) 側で、
// ここはフレーム選択・タイル計算・注記などの純粋ロジックを持つ。
public final class Radar {
    private Radar() {}

    // 📡 フレームがこの分数を超えて古い場合は「古い観測」として注記する
    public static final long STALE_MINUTES = 20;

    public static final String JMA_NOWCAST_NOTE = "気象庁ナウキャストは z8 ネイティブ表示";
    public static final String RAINVIEWER_RESOLUTION_NOTE = "RainViewerは z7 タイルを2倍拡大表示のため実効解像度が低下します";

    // ----------------------------------------
    // 🌧️ RainViewer
    // ----------------------------------------
    // 📝 weather-maps.json から最新フレームの path を取り出す
    public static String latestPath(Object root) {
        List<Object> past = Json.arr(Json.obj(Json.obj(root).get("radar")).get("past"));
        if (past.isEmpty()) return "";
        return Json.str(Json.obj(past.get(past.size() - 1)).get("path"));
    }

    public static Models.RadarFrame frame() throws Exception {
        Object root = Http.fetchJson("https://api.rainviewer.com/public/weather-maps.json");
        List<Object> past = Json.arr(Json.obj(Json.obj(root).get("radar")).get("past"));
        if (past.isEmpty()) throw new IllegalStateException("レーダーフレームがありません");
        Map<String, Object> last = Json.obj(past.get(past.size() - 1));
        return new Models.RadarFrame(Json.str(last.get("path")), (long) Json.dbl(last.get("time"), 0));
    }

    public static String tileUrl(String path, int zoom, int x, int y) {
        return "https://tilecache.rainviewer.com" + path + "/256/" + zoom + "/" + x + "/" + y + "/2/1_1.png";
    }

    // 🕐 フレーム時刻が現在時刻より何分前か (未来側・不明は 0)。純粋関数
    public static long frameAgeMinutes(long frameEpochSec, long nowMs) {
        if (frameEpochSec <= 0) return 0;
        long ageMs = nowMs - frameEpochSec * 1000L;
        return ageMs <= 0 ? 0 : ageMs / 60_000L;
    }

    // 🖥️ フレームの相対時刻表示。例: 「約5分前の観測」。閾値
    // (STALE_MINUTES) を超えた場合は古い旨の注記を付ける。
    // フレーム時刻が不明 (0以下) の場合は ""
    public static String frameAgeNote(long frameEpochSec, long nowMs) {
        if (frameEpochSec <= 0) return "";
        long minutes = frameAgeMinutes(frameEpochSec, nowMs);
        String note = "約" + minutes + "分前の観測";
        if (minutes > STALE_MINUTES) {
            note += "（" + STALE_MINUTES + "分以上前のため、最新の状況と異なる場合があります）";
        }
        return note;
    }

    // 📡 RainViewer のレーダータイルは z7 までしか提供されない
    // (z8 以上は「Zoom Level Not Supported」のバナー画像が返る)
    // ため、ベース地図 (z8 の 3×3 合成、原点 originX,originY = 中心
    // タイル-1) の上に重ねる雨タイルは z7 で取得し、512×512 に拡大
    // して描く。ここではカバー範囲の計算だけを行う純粋関数。
    // 戻り値の各要素は {tx, ty, drawX, drawY} (z7 タイル座標と
    // キャンバス上の描画位置)
    public static List<int[]> coverTiles(int originX, int originY) {
        List<int[]> tiles = new ArrayList<>();
        int lastX = Math.floorDiv(originX + 2, 2);
        int lastY = Math.floorDiv(originY + 2, 2);
        for (int ty = Math.floorDiv(originY, 2); ty <= lastY; ty++) {
            for (int tx = Math.floorDiv(originX, 2); tx <= lastX; tx++) {
                tiles.add(new int[]{tx, ty, (2 * tx - originX) * 256, (2 * ty - originY) * 256});
            }
        }
        return tiles;
    }

    // ----------------------------------------
    // 🗾 国土地理院タイル
    // ----------------------------------------
    public static String gsiTileUrl(int zoom, int x, int y) {
        return "https://cyberjapandata.gsi.go.jp/xyz/std/" + zoom + "/" + x + "/" + y + ".png";
    }

    // ----------------------------------------
    // 🌧️ 気象庁降水ナウキャスト (第2ソース)
    // ----------------------------------------
    // 無料・キー不要。対象時刻一覧は targetTimes_N1.json (新しい順、
    // 5分間隔。N1 は実況で basetime == validtime)、タイルは
    // nowc/{basetime}/none/{validtime}/surf/hrpns/{z}/{x}/{y}.png。
    // z8 ネイティブで取得できるため、RainViewer (z7 を2倍拡大) の
    // 解像度を補完できる。RainViewer 障害時のフォールバックも兼ねる
    public static String jmaNowcastTileUrl(String basetime, String validtime, int zoom, int x, int y) {
        return "https://www.jma.go.jp/bosai/jmatile/data/nowc/" + basetime + "/none/" + validtime
                + "/surf/hrpns/" + zoom + "/" + x + "/" + y + ".png";
    }

    // 🚀 targetTimes_N1.json の先頭 (最新) で hrpns を含むエントリを取り出す
    public static Models.JmaNowcastFrame parseTargetTimes(Object root) {
        for (Object entryObj : Json.arr(root)) {
            Map<String, Object> entry = Json.obj(entryObj);
            boolean hasHrpns = false;
            for (Object element : Json.arr(entry.get("elements"))) {
                if ("hrpns".equals(Json.str(element))) hasHrpns = true;
            }
            String basetime = Json.str(entry.get("basetime"));
            String validtime = Json.str(entry.get("validtime"));
            if (hasHrpns && !basetime.isEmpty() && !validtime.isEmpty()) {
                return new Models.JmaNowcastFrame(basetime, validtime);
            }
        }
        return null;
    }

    public static Models.JmaNowcastFrame jmaNowcastFrame() throws Exception {
        Object root = Http.fetchJson("https://www.jma.go.jp/bosai/jmatile/data/nowc/targetTimes_N1.json");
        Models.JmaNowcastFrame frame = parseTargetTimes(root);
        if (frame == null) throw new IllegalStateException("ナウキャストの対象時刻がありません");
        return frame;
    }

    // 🌐 タイルの部分欠落の注記。全部取得できた場合は ""、一部欠落
    // なら取得枚数を明示する (無言だと「雨がない」ように見えるため)
    public static String tileCoverageNote(String label, int expected, int got) {
        if (expected <= 0 || got >= expected) return "";
        if (got <= 0) return label + "タイルを取得できませんでした（0/" + expected + "枚）";
        return label + "タイルの一部を取得できませんでした（" + got + "/" + expected + "枚）";
    }
}
