package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// ========================================
// 📝 Json (軽量 JSON パーサ／生成・外部依存なし)
// ========================================
// 📌 標準ライブラリのみで動かすための最小実装。値は Map / List /
// String / Long / Double / Boolean / null で表す。
// 📝 アクセサ (obj / arr / str / dbl / bool) は型が合わない場合に
// 空のコレクションや既定値を返すため、呼び出し側は null チェック
// なしに走査できる。
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    // ----------------------------------------
    // 🔍 パース
    // ----------------------------------------
    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private Object readValue() {
        if (pos >= text.length()) return null;
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't', 'f' -> readBoolean();
            case 'n' -> {
                pos += 4; // null
                yield null;
            }
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ':') pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < text.length() && text.charAt(pos) == '}') pos++;
            break;
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (pos < text.length() && text.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < text.length() && text.charAt(pos) == ']') pos++;
            break;
        }
        return list;
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // '"'
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < text.length()) {
                char esc = text.charAt(pos++);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'u' -> {
                        if (pos + 4 <= text.length()) {
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean readBoolean() {
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        pos += 5; // false
        return Boolean.FALSE;
    }

    private Number readNumber() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        String num = text.substring(start, pos);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        } catch (Exception e) {
            try {
                return Double.parseDouble(num);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    // ----------------------------------------
    // 🔧 アクセサ
    // ----------------------------------------
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static String str(Object value, String fallback) {
        String s = str(value);
        return s.isEmpty() ? fallback : s;
    }

    public static double dbl(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(str(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(str(value));
    }

    // ----------------------------------------
    // ✏️ 生成
    // ----------------------------------------
    public static String escape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }
}
