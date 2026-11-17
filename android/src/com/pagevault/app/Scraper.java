package com.pagevault.app;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Scraper {
    private static final String USER_AGENT = "PageVault/1.0 (local offline reader)";
    private static final int MAX_HTML_BYTES = 12 * 1024 * 1024;
    private static final long MAX_ASSET_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ASSETS = 160;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 90_000;
    private static final Pattern CHARSET_PATTERN =
            Pattern.compile("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:([^;,]+)?(;base64)?,([\\s\\S]*)$", Pattern.CASE_INSENSITIVE);

    private static final Set<String> FORBIDDEN_TAGS = new HashSet<>(Arrays.asList(
            "script", "style", "noscript", "iframe", "frame", "frameset", "object", "embed",
            "form", "input", "button", "select", "textarea", "svg", "canvas", "template",
            "dialog", "link", "meta", "nav", "footer", "aside"
    ));
    private static final Set<String> ALLOWED_TAGS = new HashSet<>(Arrays.asList(
            "a", "article", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup",
            "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure", "h1", "h2",
            "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "mark", "ol", "p", "picture", "pre",
            "q", "s", "section", "small", "source", "span", "strike", "strong", "sub", "summary",
            "sup", "table", "tbody", "td", "tfoot", "th", "thead", "time", "tr", "u", "ul",
            "video", "audio"
    ));
    private static final Set<String> DOWNLOAD_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4", "webm", "mov", "m4v", "mkv", "mp3", "wav", "m4a", "ogg", "flac", "pdf",
            "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "epub",
            "jpg", "jpeg", "png", "gif", "webp", "svg", "avif"
    ));
    private static final Set<String> NOISE_TOKENS = new HashSet<>(Arrays.asList(
            "ad", "ads", "advert", "advertisement", "cookie", "cookies", "popup",
            "modal", "share", "sharing", "social", "comment", "comments", "related",
            "recommend", "recommended", "sidebar", "newsletter", "subscribe",
            "topnav", "topbar", "navbar", "navigation", "breadcrumb", "breadcrumbs",
            "pagination", "footer", "menu"
    ));

    private Scraper() {
    }

    public interface ProgressListener {
        void onProgress(int percent, String message);
    }

    public static final class PageResult {
        public final File directory;
        public final String id;
        public final String title;
        public final int images;
        public final int videos;
        public final int audios;
        public final int attachments;
        public final long bytes;

        PageResult(File directory, String id, String title, int images, int videos,
                   int audios, int attachments, long bytes) {
            this.directory = directory;
            this.id = id;
            this.title = title;
            this.images = images;
            this.videos = videos;
            this.audios = audios;
            this.attachments = attachments;
            this.bytes = bytes;
        }
    }

    private static final class FetchedPage {
        final String url;
        final String html;

        FetchedPage(String url, String html) {
            this.url = url;
            this.html = html;
        }
    }

    private static final class AssetSlot {
        final String url;
        final String kind;
        String localName;
        String mime;
        long bytes;

        AssetSlot(String url, String kind) {
            this.url = url;
            this.kind = kind;
        }
    }

    public static String normalizeUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("请输入网页网址");
        }
        if (!value.matches("(?i)^https?://.*")) {
            value = "https://" + value;
        }
        try {
            URL url = new URL(value);
            if (!"http".equalsIgnoreCase(url.getProtocol())
                    && !"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IllegalArgumentException("仅支持 http 或 https 网页");
            }
            if (url.getHost() == null || url.getHost().isEmpty()) {
                throw new IllegalArgumentException("网址缺少有效域名");
            }
            return url.toExternalForm();
        } catch (IOException e) {
            throw new IllegalArgumentException("网址格式不正确", e);
        }
    }

    public static PageResult scrape(String input, File pagesRoot, ProgressListener progress)
            throws Exception {
        String pageUrl = normalizeUrl(input);
        if (!pagesRoot.exists() && !pagesRoot.mkdirs()) {
            throw new IOException("无法创建保存目录");
        }

        String id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "_" + randomSuffix();
        File tempDir = new File(pagesRoot, ".tmp_" + id);
        File finalDir = new File(pagesRoot, id);
        File assetsDir = new File(tempDir, "assets");
        if (!assetsDir.mkdirs()) {
            throw new IOException("无法创建资源目录");
        }

        try {
            report(progress, 2, "正在读取网页");
            FetchedPage fetched = fetchHtml(pageUrl);
            pageUrl = fetched.url;

            report(progress, 8, "正在分析正文和媒体");
            Document parsed = Jsoup.parse(fetched.html, pageUrl);
            parsed.outputSettings().prettyPrint(false);
            String title = extractTitle(parsed, pageUrl);
            Element source = chooseContent(parsed);
            Element content = source.clone();
            removeUnsafeAndNoise(content);

            LinkedHashMap<String, AssetSlot> assets = collectAssets(parsed, pageUrl);
            List<AssetSlot> slots = new ArrayList<>(assets.values());
            if (slots.size() > MAX_ASSETS) {
                slots = new ArrayList<>(slots.subList(0, MAX_ASSETS));
            }

            long totalBytes = 0;
            for (int i = 0; i < slots.size(); i++) {
                AssetSlot slot = slots.get(i);
                int percent = 10 + (int) Math.round((i * 72.0) / Math.max(1, slots.size()));
                report(progress, percent, "正在保存资源 " + (i + 1) + "/" + slots.size());
                long downloaded = 0;
                try {
                    downloaded = downloadAsset(slot, assetsDir, pageUrl);
                } catch (Exception ignored) {
                    slot.localName = null;
                }
                totalBytes += downloaded;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new IOException("本页资源已超过 2 GB 限制");
                }
            }

            report(progress, 84, "正在生成本地阅读页");
            applyLocalAssetLinks(content, pageUrl, assets);
            sanitize(content);
            String readerHtml = buildReaderHtml(title, pageUrl, content, assets);
            writeUtf8(new File(tempDir, "index.html"), readerHtml);
            writeUtf8(new File(tempDir, "article.txt"), content.text());

            int images = downloadedCount(assets, "image");
            int videos = downloadedCount(assets, "video");
            int audios = downloadedCount(assets, "audio");
            int attachments = downloadedCount(assets, "attachment");
            long pageBytes = directorySize(tempDir);
            JSONObject manifest = new JSONObject();
            manifest.put("id", id);
            manifest.put("title", title);
            manifest.put("url", pageUrl);
            manifest.put("capturedAt", new Date().getTime());
            manifest.put("reader", "index.html");
            manifest.put("article", "article.txt");
            manifest.put("bytes", pageBytes);
            manifest.put("summary", abbreviate(content.text(), 220));
            JSONObject counts = new JSONObject();
            counts.put("images", images);
            counts.put("videos", videos);
            counts.put("audios", audios);
            counts.put("attachments", attachments);
            manifest.put("assets", counts);
            manifest.put("licenseNotice", "仅保存用户有权访问和保存的内容。");
            writeUtf8(new File(tempDir, "manifest.json"), manifest.toString(2));

            moveDirectory(tempDir.toPath(), finalDir.toPath());
            report(progress, 100, "已保存到本地");
            return new PageResult(finalDir, id, title, images, videos, audios, attachments, pageBytes);
        } catch (Exception e) {
            deleteRecursively(tempDir);
            throw e;
        }
    }

    private static FetchedPage fetchHtml(String pageUrl) throws IOException {
        HttpURLConnection connection = openConnection(pageUrl, pageUrl, "text/html,*/*;q=0.8");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("网页返回 HTTP " + status);
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_HTML_BYTES) {
                throw new IOException("网页 HTML 超过 12 MB 限制");
            }
            byte[] bytes = readLimited(connection.getInputStream(), MAX_HTML_BYTES);
            String contentType = connection.getContentType();
            String charset = charsetFrom(contentType);
            String html = new String(bytes, Charset.forName(charset));
            String finalUrl = normalizeUrl(connection.getURL().toExternalForm());
            return new FetchedPage(finalUrl, html);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url, String referer, String accept)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Referer", referer);
        return connection;
    }

    private static String extractTitle(Document doc, String pageUrl) {
        String[] selectors = {
                "meta[property=og:title]", "meta[name=twitter:title]", "h1", "title"
        };
        for (String selector : selectors) {
            Element element = doc.selectFirst(selector);
            if (element != null) {
                String value = "meta".equals(element.tagName())
                        ? element.attr("content") : element.text();
                value = value == null ? "" : value.replaceAll("\\s+", " ").trim();
                if (!value.isEmpty()) {
                    return abbreviate(value, 160);
                }
            }
        }
        try {
            return new URL(pageUrl).getHost();
        } catch (IOException ignored) {
            return "未命名网页";
        }
    }

    private static Element chooseContent(Document doc) {
        String[] selectors = {
                "article", "main", "[role=main]", "#main", "#content",
                ".article-content", ".post-content", ".entry-content",
                ".markdown-body", ".prose", ".content"
        };
        for (String selector : selectors) {
            Element best = null;
            for (Element candidate : doc.select(selector)) {
                if (candidate.text().trim().length() >= 40
                        && (best == null || candidate.text().length() > best.text().length())) {
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return doc.body();
    }

    private static void removeUnsafeAndNoise(Element root) {
        root.select("script, style, noscript, iframe, frame, frameset, object, embed, form, "
                + "input, button, select, textarea, svg, canvas, template, dialog, nav, footer, "
                + "aside, [aria-hidden=true], [hidden]").remove();
        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element != root && element.parent() != null && isNoise(element)) {
                element.remove();
            }
        }
    }

    private static boolean isNoise(Element element) {
        if (element.hasAttr("hidden")
                || "true".equalsIgnoreCase(element.attr("aria-hidden"))) {
            return true;
        }
        String values = element.className() + " " + element.id();
        for (String token : values.split("[^A-Za-z0-9]+")) {
            if (!token.isEmpty() && NOISE_TOKENS.contains(token.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static LinkedHashMap<String, AssetSlot> collectAssets(Document doc, String baseUrl) {
        LinkedHashMap<String, AssetSlot> assets = new LinkedHashMap<>();
        for (Element image : doc.select("img, picture source")) {
            String url = firstResolvable(baseUrl, attr(image, "src"), attr(image, "data-src"),
                    attr(image, "data-original"), attr(image, "data-lazy-src"),
                    attr(image, "data-url"), largestSrcSet(attr(image, "srcset")));
            addAsset(assets, url, "image");
        }
        for (Element media : doc.select("video")) {
            addAsset(assets, resolve(baseUrl, media.attr("src")), "video");
            addAsset(assets, resolve(baseUrl, media.attr("poster")), "image");
        }
        for (Element source : doc.select("video source")) {
            addAsset(assets, resolve(baseUrl, source.attr("src")), "video");
        }
        for (Element audio : doc.select("audio, audio source")) {
            addAsset(assets, resolve(baseUrl, audio.attr("src")), "audio");
        }
        for (Element link : doc.select("a[href]")) {
            String url = resolve(baseUrl, link.attr("href"));
            if (url != null && hasDownloadExtension(url)) {
                addAsset(assets, url, "attachment");
            }
        }
        return assets;
    }

    private static void addAsset(Map<String, AssetSlot> assets, String url, String kind) {
        if (url != null && !url.isEmpty() && !assets.containsKey(url) && assets.size() < MAX_ASSETS) {
            assets.put(url, new AssetSlot(url, kind));
        }
    }

    private static long downloadAsset(AssetSlot slot, File assetsDir, String referer)
            throws IOException {
        if (isStreamingManifest(slot.url)) {
            return 0;
        }
        if (slot.url.startsWith("data:")) {
            return saveDataUrl(slot, assetsDir);
        }
        HttpURLConnection connection = openConnection(slot.url, referer, "*/*");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                return 0;
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_ASSET_BYTES) {
                return 0;
            }
            String mime = connection.getContentType();
            if (mime != null && mime.toLowerCase(Locale.US).startsWith("text/html")) {
                return 0;
            }
            String extension = extensionFor(mime, slot.url);
            String localName = assetHash(slot.url) + "." + extension;
            File target = new File(assetsDir, localName);
            long bytes = copyLimited(connection.getInputStream(), target, MAX_ASSET_BYTES);
            if (bytes <= 0) {
                Files.deleteIfExists(target.toPath());
                return 0;
            }
            slot.localName = localName;
            slot.mime = mime;
            slot.bytes = bytes;
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private static long saveDataUrl(AssetSlot slot, File assetsDir) throws IOException {
        Matcher matcher = DATA_URL_PATTERN.matcher(slot.url);
        if (!matcher.matches()) {
            return 0;
        }
        String mime = matcher.group(1) == null ? "application/octet-stream" : matcher.group(1);
        boolean base64 = matcher.group(2) != null;
        String payload = matcher.group(3);
        byte[] data;
        try {
            data = base64 ? Base64.getDecoder().decode(payload)
                    : URLDecoder.decode(payload, StandardCharsets.UTF_8.name())
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return 0;
        }
        if (data.length == 0 || data.length > 8 * 1024 * 1024) {
            return 0;
        }
        String localName = assetHash(slot.url) + "." + extensionFor(mime, slot.url);
        File target = new File(assetsDir, localName);
        Files.write(target.toPath(), data);
        slot.localName = localName;
        slot.mime = mime;
        slot.bytes = data.length;
        return data.length;
    }

    private static void applyLocalAssetLinks(Element content, String baseUrl,
                                              Map<String, AssetSlot> assets) {
        for (Element element : content.select("img, picture source")) {
            String original = firstResolvable(baseUrl, attr(element, "src"),
                    attr(element, "data-src"), attr(element, "data-original"),
                    attr(element, "data-lazy-src"), attr(element, "data-url"),
                    largestSrcSet(attr(element, "srcset")));
            if (original != null) {
                AssetSlot slot = assets.get(original);
                if (slot != null && slot.localName != null) {
                    element.attr("src", "assets/" + slot.localName);
                } else {
                    element.removeAttr("src");
                }
            }
            element.removeAttr("srcset");
            element.removeAttr("data-src");
            element.removeAttr("data-original");
            element.removeAttr("data-lazy-src");
            element.removeAttr("data-url");
        }
        for (Element element : content.select("video, audio, source")) {
            String original = resolve(baseUrl, element.attr("src"));
            AssetSlot slot = assets.get(original);
            if (slot != null && slot.localName != null) {
                element.attr("src", "assets/" + slot.localName);
                element.attr("controls", "");
                if ("video".equals(element.tagName())) {
                    element.attr("preload", "metadata");
                }
            } else {
                element.removeAttr("src");
            }
        }
        for (Element video : content.select("video")) {
            String poster = resolve(baseUrl, video.attr("poster"));
            AssetSlot slot = assets.get(poster);
            if (slot != null && slot.localName != null) {
                video.attr("poster", "assets/" + slot.localName);
            } else {
                video.removeAttr("poster");
            }
        }
        for (Element link : content.select("a[href]")) {
            String original = resolve(baseUrl, link.attr("href"));
            AssetSlot slot = assets.get(original);
            if (slot != null && slot.localName != null) {
                link.attr("href", "assets/" + slot.localName);
                link.attr("download", "");
            }
        }
    }

    private static void sanitize(Element root) {
        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element.parent() == null) {
                continue;
            }
            String tag = element.tagName().toLowerCase(Locale.US);
            if ("html".equals(tag) || "body".equals(tag) || "head".equals(tag)) {
                continue;
            }
            if (FORBIDDEN_TAGS.contains(tag)) {
                element.remove();
                continue;
            }
            if (!ALLOWED_TAGS.contains(tag)) {
                element.unwrap();
                continue;
            }
            for (Attribute attribute : new ArrayList<>(element.attributes().asList())) {
                if (!isAllowedAttribute(tag, attribute.getKey())) {
                    element.removeAttr(attribute.getKey());
                }
            }
            if ("a".equals(tag)) {
                String href = element.attr("href").trim();
                if (href.isEmpty() || (!href.startsWith("#") && !href.startsWith("assets/")
                        && !href.matches("(?i)^(https?://|mailto:).*"))) {
                    element.removeAttr("href");
                }
            }
        }
    }

    private static boolean isAllowedAttribute(String tag, String attribute) {
        String key = attribute.toLowerCase(Locale.US);
        if ("title".equals(key)) {
            return true;
        }
        if ("a".equals(tag)) {
            return "href".equals(key) || "download".equals(key);
        }
        if ("img".equals(tag) || "picture".equals(tag) || "source".equals(tag)) {
            return "src".equals(key) || "alt".equals(key) || "width".equals(key)
                    || "height".equals(key) || "type".equals(key);
        }
        if ("video".equals(tag) || "audio".equals(tag)) {
            return "src".equals(key) || "poster".equals(key) || "controls".equals(key)
                    || "preload".equals(key) || "width".equals(key) || "height".equals(key);
        }
        if ("td".equals(tag) || "th".equals(tag)) {
            return "colspan".equals(key) || "rowspan".equals(key);
        }
        if ("ol".equals(tag)) {
            return "start".equals(key);
        }
        if ("time".equals(tag)) {
            return "datetime".equals(key);
        }
        return false;
    }

    private static String buildReaderHtml(String title, String pageUrl, Element content,
                                          Map<String, AssetSlot> assets) {
        String host;
        try {
            host = new URL(pageUrl).getHost();
        } catch (IOException ignored) {
            host = pageUrl;
        }
        String captured = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date());
        int images = downloadedCount(assets, "image");
        int videos = downloadedCount(assets, "video");
        int audios = downloadedCount(assets, "audio");
        int attachments = downloadedCount(assets, "attachment");
        String meta = images + " 张图片 · " + videos + " 个视频";
        if (audios > 0) {
            meta += " · " + audios + " 个音频";
        }
        if (attachments > 0) {
            meta += " · " + attachments + " 个附件";
        }
        String anchor = escapeHtml(host);
        String source = escapeHtml(pageUrl);
        String heading = escapeHtml(title);
        String styles = "<meta http-equiv=\"Content-Security-Policy\" "
                + "content=\"default-src 'self' data: blob:; img-src 'self' data: blob:; "
                + "media-src 'self' data: blob:; style-src 'unsafe-inline'; script-src 'none'; "
                + "object-src 'none'; base-uri 'none'; form-action 'none'\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>"
                + ":root{color-scheme:light;--ink:#24312d;--muted:#6d7974;--paper:#faf8f2;"
                + "--line:#dedbd2;--accent:#e8743b;--green:#1e5b4f}"
                + "*{box-sizing:border-box}html{background:#eeeae1}"
                + "body{margin:0;background:var(--paper);color:var(--ink);"
                + "font:18px/1.78 Georgia,'Noto Serif SC','Songti SC',serif}"
                + ".wrap{max-width:820px;margin:0 auto;padding:34px 28px 80px}"
                + ".eyebrow{font:700 12px/1.4 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "letter-spacing:.16em;color:var(--accent);text-transform:uppercase}"
                + "h1{font-size:clamp(32px,6vw,52px);line-height:1.12;letter-spacing:-.025em;"
                + "margin:12px 0 18px;color:#173c34}"
                + ".meta{padding-bottom:22px;margin-bottom:30px;border-bottom:1px solid var(--line);"
                + "font:14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:var(--muted)}"
                + ".meta a{color:var(--green);text-decoration:none;border-bottom:1px solid #9cb6ac}"
                + "article{overflow-wrap:anywhere}article p{margin:0 0 1.15em}"
                + "article h1,article h2,article h3,article h4{font-family:-apple-system,BlinkMacSystemFont,"
                + "'Segoe UI','Noto Sans SC',sans-serif;line-height:1.28;color:#173c34;"
                + "margin:1.8em 0 .75em}"
                + "a{color:#166b5b}img,video{display:block;max-width:100%;height:auto;margin:24px auto;"
                + "border-radius:8px;background:#e8e4da}audio{display:block;width:100%;margin:20px 0}"
                + "figure{margin:28px 0}figcaption{font:13px/1.5 sans-serif;color:var(--muted);"
                + "text-align:center;margin-top:-14px}"
                + "blockquote{margin:26px 0;padding:4px 0 4px 22px;border-left:4px solid var(--accent);"
                + "color:#4d5d57;font-style:italic}"
                + "pre{overflow:auto;padding:18px;background:#e9eee9;border-radius:8px;"
                + "font:14px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace}"
                + "code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:.9em}"
                + "table{width:100%;border-collapse:collapse;font-size:15px;margin:24px 0}"
                + "th,td{border:1px solid var(--line);padding:9px;text-align:left}"
                + ".footer{margin-top:54px;padding-top:20px;border-top:1px solid var(--line);"
                + "font:12px/1.7 sans-serif;color:var(--muted)}"
                + "@media(max-width:560px){.wrap{padding:24px 20px 60px}body{font-size:17px}"
                + "h1{font-size:34px}}"
                + "</style>";
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>" + heading + "</title>" + styles + "</head><body><main class=\"wrap\">"
                + "<div class=\"eyebrow\">PAGEVAULT · 本地保存</div>"
                + "<h1>" + heading + "</h1>"
                + "<div class=\"meta\"><a href=\"" + source + "\" rel=\"noreferrer\">"
                + anchor + "</a><br>" + meta + " · " + escapeHtml(captured) + "</div>"
                + "<article>" + content.html() + "</article>"
                + "<div class=\"footer\">此副本由拾页保存。远端脚本和表单已移除；"
                + "请在遵守网站条款与版权要求的前提下使用。</div>"
                + "</main></body></html>";
    }

    private static int downloadedCount(Map<String, AssetSlot> assets, String kind) {
        int count = 0;
        for (AssetSlot slot : assets.values()) {
            if (kind.equals(slot.kind) && slot.localName != null) {
                count++;
            }
        }
        return count;
    }

    private static String firstResolvable(String baseUrl, String... candidates) {
        for (String candidate : candidates) {
            String resolved = resolve(baseUrl, candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static String attr(Element element, String name) {
        return element.hasAttr(name) ? element.attr(name) : null;
    }

    private static String largestSrcSet(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String[] candidates = value.split(",");
        return candidates[candidates.length - 1].trim().split("\\s+")[0];
    }

    private static String resolve(String baseUrl, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("javascript:")) {
            return null;
        }
        if (trimmed.startsWith("data:")) {
            return trimmed;
        }
        try {
            URI resolved = new URI(baseUrl).resolve(trimmed);
            String scheme = resolved.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return resolved.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean hasDownloadExtension(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) {
                return false;
            }
            return DOWNLOAD_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.US));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isStreamingManifest(String url) {
        try {
            String path = new URL(url).getPath().toLowerCase(Locale.US);
            return path.endsWith(".m3u8") || path.endsWith(".mpd");
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String extensionFor(String mime, String url) {
        String normalized = mime == null ? "" : mime.split(";")[0].trim().toLowerCase(Locale.US);
        switch (normalized) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "image/avif": return "avif";
            case "image/svg+xml": return "svg";
            case "video/mp4": return "mp4";
            case "video/webm": return "webm";
            case "video/quicktime": return "mov";
            case "audio/mpeg": return "mp3";
            case "audio/mp4": return "m4a";
            case "audio/wav":
            case "audio/x-wav": return "wav";
            case "audio/ogg": return "ogg";
            case "audio/flac": return "flac";
            case "application/pdf": return "pdf";
            case "application/zip": return "zip";
            default:
                break;
        }
        if (!url.startsWith("data:")) {
            try {
                String path = new URL(url).getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                int dot = name.lastIndexOf('.');
                if (dot >= 0 && dot < name.length() - 1) {
                    String candidate = name.substring(dot + 1).toLowerCase(Locale.US);
                    if (candidate.matches("[a-z0-9]{1,8}")) {
                        return candidate;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return "bin";
    }

    private static String assetHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                result.append(String.format(Locale.US, "%02x", bytes[i]));
            }
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String randomSuffix() {
        return Long.toHexString(System.nanoTime()).substring(0, 6);
    }

    private static String charsetFrom(String contentType) {
        if (contentType != null) {
            Matcher matcher = CHARSET_PATTERN.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1)).name();
                } catch (Exception ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8.name();
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("内容超过大小限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static long copyLimited(InputStream input, File target, long limit) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        long total = 0;
        try (InputStream stream = input; FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("单个资源超过 512 MB 限制");
                }
                output.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp.toPath());
            throw e;
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return total;
    }

    private static void writeUtf8(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    static long directorySize(File directory) {
        long bytes = 0;
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            bytes += child.isDirectory() ? directorySize(child) : child.length();
        }
        return bytes;
    }

    static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String abbreviate(String value, int maxLength) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 1) + "…";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void report(ProgressListener listener, int percent, String message) {
        if (listener != null) {
            listener.onProgress(percent, message);
        }
    }
}
