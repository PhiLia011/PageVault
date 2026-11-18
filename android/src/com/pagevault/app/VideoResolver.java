package com.pagevault.app;

import android.content.Context;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VideoResolver {
    private static final String WORKING_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/132.0.0.0 Safari/537.36";
    private static final String IOS_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 "
                    + "Mobile/15E148 Safari/604.1 Edg/132.0.0.0";
    private static final Pattern BV_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9])(?<id>BV[0-9A-Za-z]{10})");
    private static final Pattern AV_PATTERN =
            Pattern.compile("(?<![A-Za-z0-9])av(?<id>\\d{6,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_PATTERN =
            Pattern.compile("[?&]p=(\\d{1,3})");
    private static final Pattern DOUYIN_ID_PATTERN = Pattern.compile(
            "(?:video|note|slides|share/video|share/note|share/slides)/"
                    + "(?<id>\\d{10,})|aweme_id[=:/\\s]+(?<id2>\\d{10,})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTER_PATTERN = Pattern.compile(
            "window\\._ROUTER_DATA\\s*=\\s*(.*?)</script>", Pattern.DOTALL);
    private static final Pattern INIT_STATE_PATTERN = Pattern.compile(
            "window\\.INIT_STATE\\s*=\\s*(.*?)</script>", Pattern.DOTALL);
    private static final Pattern INITIAL_STATE_PATTERN = Pattern.compile(
            "window\\.__INITIAL_STATE__=(.*?)</script>", Pattern.DOTALL);
    private static final Set<String> YTDLP_HOSTS = new HashSet<>(Arrays.asList(
            "youtube.com", "youtu.be", "tiktok.com", "instagram.com", "vimeo.com",
            "dailymotion.com", "twitch.tv", "facebook.com", "x.com", "twitter.com",
            "reddit.com", "soundcloud.com", "acfun.cn", "bilibili.com", "b23.tv",
            "douyin.com", "iesdouyin.com", "kuaishou.com", "xiaohongshu.com"
    ));
    private static final Object YTDLP_INIT_LOCK = new Object();
    private static boolean youtubeDlInitialized;

    private VideoResolver() {
    }

    public static final class ResolvedVideo {
        public final String title;
        public final String sourceUrl;
        public final String platform;
        public final String author;
        public final String description;
        public final List<ResolvedMedia> media;

        ResolvedVideo(String title, String sourceUrl, String platform, String author,
                      String description, List<ResolvedMedia> media) {
            this.title = title;
            this.sourceUrl = sourceUrl;
            this.platform = platform;
            this.author = author;
            this.description = description;
            this.media = media;
        }
    }

    public static final class ResolvedMedia {
        public final String url;
        public final String kind;
        public final Map<String, String> headers;

        ResolvedMedia(String url, String kind, Map<String, String> headers) {
            this.url = url;
            this.kind = kind;
            this.headers = headers;
        }
    }

    public static ResolvedVideo resolve(Context context, String inputUrl,
                                        Scraper.ProgressListener progress)
            throws Exception {
        String url = Scraper.normalizeUrl(inputUrl);
        URL parsed = new URL(url);
        String host = parsed.getHost().toLowerCase(Locale.US);
        List<String> errors = new ArrayList<>();

        if (isHost(host, "bilibili.com") || isHost(host, "b23.tv")
                || isHost(host, "bili2233.cn")) {
            report(progress, 4, "正在解析 B 站视频");
            try {
                return resolveBilibili(url);
            } catch (Exception error) {
                errors.add("B站原生解析：" + message(error));
            }
        } else if (isHost(host, "douyin.com") || isHost(host, "iesdouyin.com")) {
            report(progress, 4, "正在解析抖音视频");
            try {
                return resolveDouyin(url);
            } catch (Exception error) {
                errors.add("抖音原生解析：" + message(error));
            }
        } else if (isHost(host, "kuaishou.com") || isHost(host, "chenzhongtech.com")) {
            report(progress, 4, "正在解析快手视频");
            try {
                return resolveKuaishou(url);
            } catch (Exception error) {
                errors.add("快手原生解析：" + message(error));
            }
        } else if (isHost(host, "xiaohongshu.com") || isHost(host, "xhslink.com")
                || isHost(host, "xhslink.cn")) {
            report(progress, 4, "正在解析小红书笔记");
            try {
                return resolveXiaohongshu(url);
            } catch (Exception error) {
                errors.add("小红书原生解析：" + message(error));
            }
        }

        if (YTDLP_HOSTS.stream().anyMatch(candidate -> isHost(host, candidate))) {
            report(progress, 7, "正在通过通用解析器提取媒体");
            try {
                return resolveWithYtDlp(context, url);
            } catch (Exception error) {
                errors.add("yt-dlp：" + message(error));
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("视频解析失败。" + String.join("；", errors));
        }
        return null;
    }

    private static ResolvedVideo resolveBilibili(String inputUrl) throws Exception {
        FetchResult first = fetch(inputUrl, commonHeaders("https://www.bilibili.com/"));
        String textForIds = first.finalUrl;
        Matcher bv = BV_PATTERN.matcher(textForIds);
        Matcher av = AV_PATTERN.matcher(textForIds);
        String bvid;
        if (bv.find()) {
            bvid = bv.group("id");
        } else if (av.find()) {
            bvid = "av" + av.group("id");
        } else {
            throw new IllegalArgumentException("链接中没有 BV/av 视频编号");
        }

        int pageNumber = 1;
        Matcher pageMatch = PAGE_PATTERN.matcher(inputUrl);
        if (pageMatch.find()) {
            pageNumber = Math.max(1, Integer.parseInt(pageMatch.group(1)));
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.bilibili.com/");
        headers.put("Origin", "https://www.bilibili.com");
        headers.put("User-Agent", WORKING_UA);
        String apiKey = bvid.toLowerCase(Locale.US).startsWith("av") ? "aid" : "bvid";
        JSONObject viewRoot = new JSONObject(get(
                "https://api.bilibili.com/x/web-interface/view?" + apiKey + "="
                        + urlEncode(bvid), headers));
        if (viewRoot.optInt("code") != 0) {
            throw new IllegalStateException(viewRoot.optString("message", "视频信息接口失败"));
        }
        JSONObject data = viewRoot.getJSONObject("data");
        String canonicalBvid = data.optString("bvid", bvid);
        long cid = data.optLong("cid");
        JSONArray pages = data.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            int index = Math.min(pageNumber - 1, pages.length() - 1);
            cid = pages.getJSONObject(index).optLong("cid", cid);
        }
        if (cid <= 0) {
            throw new IllegalStateException("未获取到视频分集 CID");
        }

        JSONObject playRoot = new JSONObject(get(
                "https://api.bilibili.com/x/player/playurl?bvid=" + urlEncode(canonicalBvid)
                        + "&cid=" + cid + "&qn=64&fnval=0&fnver=0&fourk=0",
                headers));
        JSONObject playData = playRoot.optJSONObject("data");
        JSONArray durl = playData == null ? null : playData.optJSONArray("durl");
        if (playRoot.optInt("code") != 0 || durl == null || durl.length() == 0) {
            throw new IllegalStateException(playRoot.optString("message", "未找到直接视频流"));
        }
        if (durl.length() > 1) {
            throw new IllegalStateException("该视频被拆分为多个媒体分片，当前版本暂不支持合并");
        }
        String videoUrl = normalizeMediaUrl(durl.getJSONObject(0).optString("url", ""));
        if (videoUrl == null) {
            throw new IllegalStateException("B 站没有返回可下载视频地址");
        }

        List<ResolvedMedia> media = new ArrayList<>();
        String thumbnail = normalizeMediaUrl(data.optString("pic", ""));
        if (thumbnail != null) {
            media.add(new ResolvedMedia(thumbnail, "image", headers));
        }
        media.add(new ResolvedMedia(videoUrl, "video", headers));
        String title = data.optString("title", "B站视频 " + canonicalBvid);
        if (pages != null && pages.length() > 1) {
            int index = Math.min(pageNumber - 1, pages.length() - 1);
            title += "｜" + pages.getJSONObject(index).optString("part", "P" + pageNumber);
        }
        JSONObject owner = data.optJSONObject("owner");
        String source = "https://www.bilibili.com/video/" + canonicalBvid
                + (pageNumber > 1 ? "?p=" + pageNumber : "");
        return new ResolvedVideo(title, source, "哔哩哔哩",
                owner == null ? null : owner.optString("name"),
                data.optString("desc"), media);
    }

    private static ResolvedVideo resolveDouyin(String inputUrl) throws Exception {
        ensureCookies();
        FetchResult first = fetch(inputUrl, commonHeaders("https://www.iesdouyin.com/"));
        Matcher idMatcher = DOUYIN_ID_PATTERN.matcher(first.finalUrl);
        String videoId = "";
        if (idMatcher.find()) {
            videoId = idMatcher.group("id");
            if (videoId == null) {
                videoId = idMatcher.group("id2");
            }
        }
        if (videoId == null || videoId.isEmpty()) {
            throw new IllegalArgumentException("无法从分享链接识别作品 ID");
        }
        String shareUrl = "https://www.iesdouyin.com/share/video/" + videoId + "/";
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.iesdouyin.com/");
        headers.put("User-Agent", IOS_UA);

        String html = "";
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                registerTtwid();
                html = fetch(shareUrl, headers).html;
                if (html.contains("_ROUTER_DATA")) {
                    break;
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (html.isEmpty()) {
            throw new IllegalStateException("抖音分享页读取失败"
                    + (lastError == null ? "" : "：" + message(lastError)));
        }
        Matcher router = ROUTER_PATTERN.matcher(html);
        if (!router.find()) {
            throw new IllegalStateException("抖音页面中没有路由数据");
        }
        JSONObject root = new JSONObject(router.group(1).trim());
        JSONObject item = findDouyinItem(root.optJSONObject("loaderData"));
        if (item == null) {
            throw new IllegalStateException("抖音作品不存在、已删除或需要登录");
        }

        Map<String, String> mediaHeaders = new HashMap<>();
        mediaHeaders.put("Referer", shareUrl);
        mediaHeaders.put("User-Agent", IOS_UA);
        List<ResolvedMedia> media = new ArrayList<>();
        JSONObject video = item.optJSONObject("video");
        if (video != null) {
            String videoUrl = firstUrl(video.optJSONObject("play_addr"));
            videoUrl = normalizeMediaUrl(videoUrl);
            if (videoUrl != null) {
                videoUrl = videoUrl.replace("playwm", "play");
                media.add(new ResolvedMedia(videoUrl, "video", mediaHeaders));
            }
            String cover = normalizeMediaUrl(firstUrl(video.optJSONObject("cover")));
            if (cover != null) {
                media.add(new ResolvedMedia(cover, "image", mediaHeaders));
            }
        }
        JSONArray images = item.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String url = normalizeMediaUrl(firstUrl(image));
                if (url != null) {
                    media.add(new ResolvedMedia(url, "image", mediaHeaders));
                }
            }
        }
        if (media.isEmpty()) {
            throw new IllegalStateException("抖音作品没有可保存的视频或图片");
        }
        JSONObject author = item.optJSONObject("author");
        long duration = video == null ? 0 : video.optLong("duration");
        if (duration > 10_000) {
            duration /= 1000;
        }
        return new ResolvedVideo(
                abbreviate(item.optString("desc", "抖音作品 " + videoId), 180),
                shareUrl, "抖音", author == null ? null : author.optString("nickname"),
                duration > 0 ? "视频时长：" + duration + " 秒" : null, media);
    }

    private static ResolvedVideo resolveKuaishou(String inputUrl) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://v.kuaishou.com/");
        headers.put("User-Agent", IOS_UA);
        FetchResult page = fetch(inputUrl, headers);
        String pageUrl = page.finalUrl.replace("/fw/long-video/", "/fw/photo/");
        if (!pageUrl.equals(page.finalUrl)) {
            page = fetch(pageUrl, headers);
        }
        Matcher init = INIT_STATE_PATTERN.matcher(page.html);
        if (!init.find()) {
            throw new IllegalStateException("快手页面中没有作品数据");
        }
        JSONObject photo = findProperty(new JSONObject(init.group(1).trim()), "photo");
        if (photo == null) {
            throw new IllegalStateException("快手作品不存在或需要登录");
        }
        Map<String, String> mediaHeaders = new HashMap<>();
        mediaHeaders.put("Referer", page.finalUrl);
        mediaHeaders.put("User-Agent", IOS_UA);
        List<ResolvedMedia> media = new ArrayList<>();
        String videoUrl = normalizeMediaUrl(firstObjectUrl(photo.optJSONArray("mainMvUrls")));
        if (videoUrl != null) {
            media.add(new ResolvedMedia(videoUrl, "video", mediaHeaders));
        }
        String cover = normalizeMediaUrl(firstObjectUrl(photo.optJSONArray("coverUrls")));
        if (cover != null) {
            media.add(new ResolvedMedia(cover, "image", mediaHeaders));
        }
        if (media.isEmpty()) {
            throw new IllegalStateException("快手作品没有可保存的视频");
        }
        long duration = photo.optLong("duration");
        if (duration > 10_000) {
            duration /= 1000;
        }
        return new ResolvedVideo(
                abbreviate(photo.optString("caption", "快手作品"), 180),
                page.finalUrl, "快手", photo.optString("userName"),
                duration > 0 ? "视频时长：" + duration + " 秒" : null, media);
    }

    private static ResolvedVideo resolveXiaohongshu(String inputUrl) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.xiaohongshu.com/");
        headers.put("User-Agent", WORKING_UA);
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        FetchResult page = fetch(inputUrl, headers);
        Matcher state = INITIAL_STATE_PATTERN.matcher(page.html);
        if (!state.find()) {
            throw new IllegalStateException("小红书页面中没有笔记数据");
        }
        String jsonText = state.group(1).trim().replace("undefined", "null");
        JSONObject root = new JSONObject(jsonText);
        JSONObject detailMap = findProperty(root, "noteDetailMap");
        if (detailMap == null) {
            throw new IllegalStateException("小红书分享链接失效、受限或需要登录");
        }
        JSONObject note = null;
        Iterator<String> keys = detailMap.keys();
        while (keys.hasNext()) {
            JSONObject value = detailMap.optJSONObject(keys.next());
            if (value != null && value.optJSONObject("note") != null) {
                note = value.getJSONObject("note");
                break;
            }
        }
        if (note == null) {
            throw new IllegalStateException("小红书笔记内容不存在或已删除");
        }
        Map<String, String> mediaHeaders = new HashMap<>();
        mediaHeaders.put("Referer", page.finalUrl);
        mediaHeaders.put("User-Agent", WORKING_UA);
        List<ResolvedMedia> media = new ArrayList<>();
        String videoUrl = firstXiaohongshuVideoUrl(note);
        if (videoUrl != null) {
            media.add(new ResolvedMedia(videoUrl, "video", mediaHeaders));
        }
        JSONArray images = note.optJSONArray("imageList");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String value = image == null ? null : image.optString(
                        "urlDefault", image.optString("urlPre", image.optString("url")));
                String normalized = normalizeMediaUrl(value);
                if (normalized != null) {
                    media.add(new ResolvedMedia(normalized, "image", mediaHeaders));
                }
            }
        }
        if (media.isEmpty()) {
            throw new IllegalStateException("小红书笔记没有可保存的媒体");
        }
        JSONObject user = note.optJSONObject("user");
        return new ResolvedVideo(note.optString("title", "小红书笔记"),
                page.finalUrl, "小红书",
                user == null ? null : user.optString("nickname", user.optString("nickName")),
                note.optString("desc"), media);
    }

    private static ResolvedVideo resolveWithYtDlp(Context context, String url) throws Exception {
        synchronized (YTDLP_INIT_LOCK) {
            if (!youtubeDlInitialized) {
                YoutubeDL.getInstance().init(context);
                youtubeDlInitialized = true;
            }
        }
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        request.addOption("--no-warnings");
        request.addOption("--no-playlist");
        request.addOption("--socket-timeout", "20");
        request.addOption("--retries", "2");
        VideoInfo info = YoutubeDL.getInstance().getInfo(request);

        List<VideoFormat> combined = new ArrayList<>();
        if (info.getFormats() != null) {
            for (VideoFormat format : info.getFormats()) {
                if (isCombinedHttpFormat(format)) {
                    combined.add(format);
                }
            }
        }
        if (combined.isEmpty()) {
            throw new IllegalStateException(
                    "该站点只返回分离音视频或不支持的流格式，当前版本无法直接保存");
        }
        combined.sort(Comparator.comparingInt(VideoFormat::getHeight)
                .thenComparingLong(VideoFormat::getFileSize).reversed());
        VideoFormat selected = combined.get(0);
        Map<String, String> headers = selected.getHttpHeaders();
        if (headers == null || headers.isEmpty()) {
            headers = info.getHttpHeaders();
        }
        if (headers == null) {
            headers = Collections.emptyMap();
        }
        List<ResolvedMedia> media = new ArrayList<>();
        String thumbnail = normalizeMediaUrl(info.getThumbnail());
        if (thumbnail != null) {
            media.add(new ResolvedMedia(thumbnail, "image", headers));
        }
        media.add(new ResolvedMedia(selected.getUrl(), "video", headers));

        String source = info.getWebpageUrl();
        if (source == null || source.isEmpty()) {
            source = url;
        }
        String platform = info.getExtractorKey();
        if (platform == null || platform.isEmpty()) {
            platform = info.getExtractor();
        }
        String author = info.getUploader();
        if (author == null || author.isEmpty()) {
            author = info.getWebpageUrlBasename();
        }
        String description = info.getDuration() > 0
                ? String.format(Locale.CHINA, "视频时长：%d:%02d",
                info.getDuration() / 60, info.getDuration() % 60)
                : info.getDescription();
        return new ResolvedVideo(
                info.getTitle() == null || info.getTitle().isEmpty()
                        ? "解析视频" : info.getTitle(),
                source, platform == null ? "通用解析器" : platform,
                author, description, media);
    }

    private static boolean isCombinedHttpFormat(VideoFormat format) {
        String url = format.getUrl();
        String protocol = format.getFormatNote() == null ? "" : format.getFormatNote();
        String vcodec = format.getVcodec() == null ? "none" : format.getVcodec();
        String acodec = format.getAcodec() == null ? "none" : format.getAcodec();
        String ext = format.getExt() == null ? "" : format.getExt().toLowerCase(Locale.US);
        if (url == null || !url.startsWith("https://")
                || protocol.toLowerCase(Locale.US).contains("m3u8")
                || "none".equals(vcodec) || "none".equals(acodec)) {
            return false;
        }
        return "mp4".equals(ext) || "webm".equals(ext) || "mov".equals(ext);
    }

    private static JSONObject findDouyinItem(JSONObject loaderData) {
        if (loaderData == null) {
            return null;
        }
        Iterator<String> keys = loaderData.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.contains("/page")) {
                continue;
            }
            JSONObject page = loaderData.optJSONObject(key);
            JSONObject info = page == null ? null : page.optJSONObject("videoInfoRes");
            JSONArray items = info == null ? null : info.optJSONArray("item_list");
            if (items != null && items.length() > 0) {
                return items.optJSONObject(0);
            }
        }
        return null;
    }

    private static JSONObject findProperty(JSONObject object, String name) {
        if (object == null) {
            return null;
        }
        JSONObject direct = object.optJSONObject(name);
        if (direct != null) {
            return direct;
        }
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            JSONObject child = object.optJSONObject(keys.next());
            if (child != null) {
                JSONObject found = findProperty(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String firstUrl(JSONObject object) {
        if (object == null) {
            return null;
        }
        JSONArray list = object.optJSONArray("url_list");
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.length(); i++) {
            String value = list.optString(i, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String firstObjectUrl(JSONArray list) {
        if (list == null || list.length() == 0) {
            return null;
        }
        JSONObject first = list.optJSONObject(0);
        return first == null ? null : first.optString("url", null);
    }

    private static String firstXiaohongshuVideoUrl(JSONObject note) {
        JSONObject video = note.optJSONObject("video");
        JSONObject media = video == null ? null : video.optJSONObject("media");
        JSONObject stream = media == null ? null : media.optJSONObject("stream");
        if (stream == null) {
            return null;
        }
        for (String codec : new String[]{"h265", "h264", "av1", "h266"}) {
            JSONArray variants = stream.optJSONArray(codec);
            if (variants != null && variants.length() > 0) {
                JSONObject first = variants.optJSONObject(0);
                String value = first == null ? null : first.optString("masterUrl", null);
                if (value != null) {
                    return normalizeMediaUrl(value);
                }
            }
        }
        return null;
    }

    private static void registerTtwid() throws Exception {
        ensureCookies();
        JSONObject payload = new JSONObject();
        payload.put("region", "cn");
        payload.put("aid", 1768);
        payload.put("needFid", false);
        payload.put("service", "www.iesdouyin.com");
        payload.put("union", true);
        payload.put("fid", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://www.iesdouyin.com/");
        headers.put("User-Agent", IOS_UA);
        headers.put("Content-Type", "application/json");
        String response = request(
                "https://ttwid.bytedance.com/ttwid/union/register/", "POST",
                payload.toString(), headers);
        JSONObject body = new JSONObject(response);
        String callback = body.optString("redirect_url", "");
        if (!callback.isEmpty()) {
            fetch(callback, headers);
        }
    }

    private static synchronized void ensureCookies() {
        if (!(CookieHandler.getDefault() instanceof CookieManager)) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
    }

    private static FetchResult fetch(String url, Map<String, String> headers) throws Exception {
        ensureCookies();
        HttpURLConnection connection = open(url, "GET", headers);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return new FetchResult(connection.getURL().toExternalForm(),
                    readLimited(connection.getInputStream(), 20 * 1024 * 1024));
        } finally {
            connection.disconnect();
        }
    }

    private static String get(String url, Map<String, String> headers) throws Exception {
        return fetch(url, headers).html;
    }

    private static String request(String url, String method, String body,
                                  Map<String, String> headers) throws Exception {
        ensureCookies();
        HttpURLConnection connection = open(url, method, headers);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("HTTP " + status);
            }
            return readLimited(connection.getInputStream(), 10 * 1024 * 1024);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method,
                                          Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static Map<String, String> commonHeaders(String referer) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", WORKING_UA);
        headers.put("Referer", referer);
        return headers;
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IllegalStateException("解析页面超过大小限制");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalizeMediaUrl(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        value = value.trim();
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            if ("http".equalsIgnoreCase(scheme)) {
                value = "https" + value.substring(4);
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isHost(String host, String expected) {
        return host.equalsIgnoreCase(expected) || host.endsWith("." + expected);
    }

    private static String urlEncode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String abbreviate(String value, int maxLength) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength - 1) + "…";
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static void report(Scraper.ProgressListener listener, int percent, String message) {
        if (listener != null) {
            listener.onProgress(percent, message);
        }
    }

    private static final class FetchResult {
        final String finalUrl;
        final String html;

        FetchResult(String finalUrl, String html) {
            this.finalUrl = finalUrl;
            this.html = html;
        }
    }
}
