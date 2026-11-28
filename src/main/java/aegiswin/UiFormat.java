package aegiswin;

import java.awt.Color;

// ========================================
// 🎨 UiFormat (UI 整形ヘルパー: 色・文字列整形)
// ========================================
// 📌 Main (Swing GUI) から画面部品に依存しない純粋な整形だけを
// 切り出したクラス。単体テストからも検証できる。
public final class UiFormat {
    private UiFormat() {}

    // 💾 ブリーフィングキャッシュのサイズ上限 (Preferences の肥大化防止)
    static final int CACHE_MAX_BYTES = 100 * 1024;

    // 💾 キャッシュがサイズ上限以内か (UTF-8 のバイト数で判定する)
    static boolean briefingCacheFits(String serialized) {
        return serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= CACHE_MAX_BYTES;
    }

    // 🖥️ HTML 表示用に &, <, > をエスケープする
    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // 🖥️ 表で示す行 (時間別・週間) をカード本文から取り除く
    static String stripTableLines(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("🕒 時間別：") || line.startsWith("週間：")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }

    // 🎨 セクションの重要度 (level) に対応する色
    static Color levelColor(String level) {
        return switch (level == null ? "" : level) {
            case "danger" -> new Color(0xFF6B6B);
            case "warn" -> new Color(0xFFB454);
            case "ok" -> new Color(0x58C184);
            default -> new Color(0x7FA8E0);
        };
    }

    // 🌏 震度バッジの色 (引数は震度値コード: 45=5弱, 40=4, 30=3, 20=2)。
    // 5弱以上を赤とし、以下は段階的に抑える
    static Color scaleColor(int scale) {
        if (scale >= 45) return new Color(0xC93A3A);
        if (scale >= 40) return new Color(0xD97A2B);
        if (scale >= 30) return new Color(0xA8871D);
        if (scale >= 20) return new Color(0x3E6B9E);
        return new Color(0x54687F);
    }
}
