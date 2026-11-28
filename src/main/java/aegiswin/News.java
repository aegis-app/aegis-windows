package aegiswin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Future;

// ========================================
// 📰 News (ニュース要約: NHK RSS)
// ========================================
// 📌 NHK の主要 (cat0) と社会 (cat1) をマージし、防災関連を
// 優先して最大8件返す。
public final class News {
    private News() {}

    // 🔎 防災関連とみなすキーワード
    static final String[] DISASTER_KEYWORDS = {
            "地震", "津波", "警報", "特別警報", "台風", "大雨", "洪水", "土砂", "避難",
            "震度", "噴火", "熱中症", "暴風", "大雪", "竜巻", "線状降水帯"};

    static boolean isDisasterNews(String text) {
        if (text == null) return false;
        for (String keyword : DISASTER_KEYWORDS) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    // 📝 description の HTML を除去し、80字程度に切り詰める (途中で切れたら「…」)
    public static String summarize(String html) {
        if (html == null) return "";
        String text = html.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
        if (text.length() <= 82) return text;
        return text.substring(0, 80) + "…";
    }

    // 🔄 NHK RSS (RSS 2.0) をパースする。外部実体は無効化し、XXE を防ぐ
    public static List<Models.NewsItem> parseRss(String xml) {
        List<Models.NewsItem> items = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            } catch (Exception ignored) {}
            try {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            } catch (Exception ignored) {}
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            org.w3c.dom.Document doc = factory.newDocumentBuilder().parse(
                    new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName("item");
            java.text.SimpleDateFormat inputFormat =
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US);
            java.text.SimpleDateFormat outputFormat = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN);
            outputFormat.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Element item = (org.w3c.dom.Element) nodes.item(i);
                String title = childText(item, "title");
                String link = childText(item, "link");
                String description = childText(item, "description");
                String published = childText(item, "pubDate");
                if (title.isEmpty() || link.isEmpty()) continue;
                String time = "";
                try {
                    java.util.Date parsed = inputFormat.parse(published);
                    if (parsed != null) time = outputFormat.format(parsed);
                } catch (Exception ignored) {}
                items.add(new Models.NewsItem(title, summarize(description), link, time));
            }
        } catch (Exception ignored) {}
        return items;
    }

    private static String childText(org.w3c.dom.Element parent, String tag) {
        org.w3c.dom.NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return "";
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text.trim();
    }

    // 📝 主要 (cat0) と社会 (cat1) をマージし、防災関連を優先して最大8件返す
    public static List<Models.NewsItem> newsItems() throws Exception {
        Future<String> mainFuture = Http.FETCH_POOL.submit(() -> Http.fetchText("https://www3.nhk.or.jp/rss/news/cat0.xml"));
        Future<String> societyFuture = Http.FETCH_POOL.submit(() -> Http.fetchText("https://www3.nhk.or.jp/rss/news/cat1.xml"));
        LinkedHashMap<String, Models.NewsItem> byLink = new LinkedHashMap<>();
        for (Future<String> future : List.of(mainFuture, societyFuture)) {
            try {
                for (Models.NewsItem item : parseRss(future.get(Http.DISPLAY_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))) {
                    byLink.putIfAbsent(item.link(), item);
                }
            } catch (Exception ignored) {}
        }
        List<Models.NewsItem> all = new ArrayList<>(byLink.values());
        if (all.isEmpty()) throw new IllegalStateException("ニュースを取得できませんでした");
        List<Models.NewsItem> selected = new ArrayList<>();
        for (Models.NewsItem item : all) {
            if (isDisasterNews(item.title() + " " + item.summary()) && selected.size() < 8) selected.add(item);
        }
        for (Models.NewsItem item : all) {
            if (selected.size() >= 8) break;
            if (!selected.contains(item)) selected.add(item);
        }
        return selected;
    }
}
