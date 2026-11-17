using AngleSharp.Dom;
using AngleSharp.Html.Parser;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PageVault;

public sealed class ScraperService
{
    private const string UserAgent = "PageVault/1.0 (local offline reader)";
    private const int MaxHtmlBytes = 12 * 1024 * 1024;
    private const long MaxAssetBytes = 512L * 1024 * 1024;
    private const long MaxTotalBytes = 2L * 1024 * 1024 * 1024;
    private const int MaxAssets = 160;
    private const int ConnectTimeoutSeconds = 20;

    private static readonly Regex CharsetRegex = new(
        @"charset\s*=\s*[""']?([^;""'\s]+)",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex MetaCharsetRegex = new(
        @"<meta[^>]+charset\s*=\s*[""']?([^""'\s/>]+)",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly HashSet<string> ForbiddenTags = new(
        new[]
        {
            "script", "style", "noscript", "iframe", "frame", "frameset", "object",
            "embed", "form", "input", "button", "select", "textarea", "svg", "canvas",
            "template", "dialog", "link", "meta", "nav", "footer", "aside"
        },
        StringComparer.OrdinalIgnoreCase);

    private static readonly HashSet<string> AllowedTags = new(
        new[]
        {
            "a", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup",
            "dd", "del", "details", "div", "dl", "dt", "em", "figcaption", "figure",
            "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "mark", "ol",
            "p", "pre", "q", "s", "section", "small", "span", "strike", "strong",
            "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th", "thead",
            "time", "tr", "u", "ul", "video", "audio"
        },
        StringComparer.OrdinalIgnoreCase);

    private static readonly HashSet<string> DownloadExtensions = new(
        new[]
        {
            "mp4", "webm", "mov", "m4v", "mkv", "mp3", "wav", "m4a", "ogg", "flac",
            "pdf", "zip", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf",
            "csv", "epub", "jpg", "jpeg", "png", "gif", "webp", "svg", "avif"
        },
        StringComparer.OrdinalIgnoreCase);

    private static readonly HashSet<string> NoiseTokens = new(
        new[]
        {
            "ad", "ads", "advert", "advertisement", "cookie", "cookies", "popup",
            "modal", "share", "sharing", "social", "comment", "comments", "related",
            "recommend", "recommended", "sidebar", "newsletter", "subscribe",
            "topnav", "topbar", "navbar", "navigation", "breadcrumb", "breadcrumbs",
            "pagination", "footer", "menu"
        },
        StringComparer.OrdinalIgnoreCase);

    private readonly HttpClient _http;
    private readonly HtmlParser _parser = new();

    static ScraperService()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    public ScraperService()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 8,
            AutomaticDecompression = DecompressionMethods.GZip
                                    | DecompressionMethods.Deflate
                                    | DecompressionMethods.Brotli,
            CookieContainer = new CookieContainer(),
            UseCookies = true
        };
        _http = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromMinutes(5)
        };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        _http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9,en;q=0.7");
    }

    public static string NormalizeUrl(string input)
    {
        var value = input.Trim();
        if (string.IsNullOrWhiteSpace(value))
        {
            throw new ArgumentException("请输入网页网址");
        }
        if (!Regex.IsMatch(value, @"^https?://", RegexOptions.IgnoreCase))
        {
            value = "https://" + value;
        }
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
            || string.IsNullOrWhiteSpace(uri.Host))
        {
            throw new ArgumentException("网址格式不正确，仅支持 http 或 https 网页");
        }
        var builder = new UriBuilder(uri) { Fragment = "" };
        return builder.Uri.AbsoluteUri;
    }

    public async Task<ScrapeResult> ScrapeAsync(
        string input,
        string pagesRoot,
        IProgress<ScrapeProgress>? progress,
        CancellationToken cancellationToken)
    {
        var pageUrl = new Uri(NormalizeUrl(input));
        Directory.CreateDirectory(pagesRoot);

        var id = $"{DateTime.Now:yyyyMMdd_HHmmss}_{RandomSuffix()}";
        var tempDirectory = Path.Combine(pagesRoot, ".tmp_" + id);
        var finalDirectory = Path.Combine(pagesRoot, id);
        var assetsDirectory = Path.Combine(tempDirectory, "assets");
        Directory.CreateDirectory(assetsDirectory);

        try
        {
            Report(progress, 2, "正在读取网页");
            var fetched = await FetchPageAsync(pageUrl, cancellationToken);
            pageUrl = fetched.Url;

            Report(progress, 8, "正在分析正文和媒体");
            var document = await _parser.ParseDocumentAsync(fetched.Html, cancellationToken);
            var title = ExtractTitle(document, pageUrl);
            var source = ChooseContent(document);
            var assets = CollectAssets(document, pageUrl);
            var slots = assets.Values.Take(MaxAssets).ToList();

            long totalBytes = 0;
            for (var index = 0; index < slots.Count; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var slot = slots[index];
                var percent = 10 + (int)Math.Round(index * 72d / Math.Max(1, slots.Count));
                Report(progress, percent,
                    $"正在保存资源 {index + 1}/{slots.Count}");
                if (totalBytes >= MaxTotalBytes)
                {
                    break;
                }
                var downloaded = await DownloadAssetAsync(
                    slot, assetsDirectory, pageUrl, MaxTotalBytes - totalBytes,
                    cancellationToken);
                totalBytes += downloaded;
            }

            Report(progress, 84, "正在生成本地阅读页");
            var content = new StringBuilder();
            RenderChildren(source, content, pageUrl, assets);
            var contentHtml = content.ToString();
            var readerDocument = await _parser.ParseDocumentAsync(
                $"<html><body>{contentHtml}</body></html>",
                cancellationToken);
            var plainText = NormalizeWhitespace(readerDocument.Body?.TextContent ?? "");
            var imageCount = DownloadedCount(assets, "image");
            var videoCount = DownloadedCount(assets, "video");
            var audioCount = DownloadedCount(assets, "audio");
            var attachmentCount = DownloadedCount(assets, "attachment");

            if (plainText.Length < 20 && imageCount + videoCount + audioCount == 0)
            {
                throw new InvalidOperationException(
                    "未识别到可保存内容。页面可能需要登录、依赖 JavaScript 或禁止访问。");
            }

            var readerHtml = BuildReaderHtml(
                title, pageUrl, contentHtml, imageCount, videoCount, audioCount,
                attachmentCount);
            await File.WriteAllTextAsync(
                Path.Combine(tempDirectory, "index.html"),
                readerHtml,
                new UTF8Encoding(false),
                cancellationToken);
            await File.WriteAllTextAsync(
                Path.Combine(tempDirectory, "article.txt"),
                plainText,
                new UTF8Encoding(false),
                cancellationToken);

            var pageBytes = PageStore.DirectorySize(tempDirectory);
            var manifest = new
            {
                id,
                title,
                url = pageUrl.AbsoluteUri,
                capturedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                reader = "index.html",
                article = "article.txt",
                bytes = pageBytes,
                summary = Abbreviate(plainText, 220),
                assets = new
                {
                    images = imageCount,
                    videos = videoCount,
                    audios = audioCount,
                    attachments = attachmentCount
                },
                licenseNotice = "仅保存用户有权访问和保存的内容。"
            };
            await File.WriteAllTextAsync(
                Path.Combine(tempDirectory, "manifest.json"),
                JsonSerializer.Serialize(manifest, new JsonSerializerOptions
                {
                    WriteIndented = true
                }),
                new UTF8Encoding(false),
                cancellationToken);

            Directory.Move(tempDirectory, finalDirectory);
            Report(progress, 100, "已保存到本地资料库");
            return new ScrapeResult(id, title, imageCount, videoCount, audioCount,
                attachmentCount, pageBytes);
        }
        catch
        {
            TryDeleteDirectory(tempDirectory);
            throw;
        }
    }

    private async Task<FetchedPage> FetchPageAsync(Uri url, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Accept.ParseAdd("text/html,application/xhtml+xml;q=0.9,*/*;q=0.7");
        request.Headers.AcceptEncoding.ParseAdd("gzip, deflate, br");
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException(
                $"网页返回 HTTP {(int)response.StatusCode} {response.ReasonPhrase}");
        }
        if (response.Content.Headers.ContentLength > MaxHtmlBytes)
        {
            throw new InvalidOperationException("网页 HTML 超过 12 MB 限制");
        }
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        var bytes = await ReadLimitedAsync(stream, MaxHtmlBytes, cancellationToken);
        var finalUrl = response.RequestMessage?.RequestUri ?? url;
        NormalizeUrl(finalUrl.AbsoluteUri);
        var html = DecodeHtml(bytes, response.Content.Headers.ContentType?.CharSet);
        return new FetchedPage(finalUrl, html);
    }

    private static string DecodeHtml(byte[] bytes, string? headerCharset)
    {
        if (bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF)
        {
            return Encoding.UTF8.GetString(bytes, 3, bytes.Length - 3);
        }
        var charset = headerCharset;
        if (string.IsNullOrWhiteSpace(charset))
        {
            var prefix = Encoding.ASCII.GetString(bytes, 0, Math.Min(bytes.Length, 8192));
            var match = MetaCharsetRegex.Match(prefix);
            if (match.Success)
            {
                charset = match.Groups[1].Value;
            }
        }
        if (string.IsNullOrWhiteSpace(charset))
        {
            charset = "utf-8";
        }
        try
        {
            return Encoding.GetEncoding(charset.Trim('"', '\'')).GetString(bytes);
        }
        catch
        {
            return Encoding.UTF8.GetString(bytes);
        }
    }

    private static string ExtractTitle(IDocument document, Uri pageUrl)
    {
        var selectors = new[]
        {
            "meta[property='og:title']",
            "meta[name='twitter:title']",
            "h1",
            "title"
        };
        foreach (var selector in selectors)
        {
            var element = document.QuerySelector(selector);
            var value = element is null
                ? ""
                : element.LocalName == "meta"
                    ? element.GetAttribute("content") ?? ""
                    : element.TextContent;
            value = NormalizeWhitespace(value);
            if (!string.IsNullOrWhiteSpace(value))
            {
                return Abbreviate(value, 160);
            }
        }
        return pageUrl.Host;
    }

    private static IElement ChooseContent(IDocument document)
    {
        var selectors = new[]
        {
            "article",
            "main",
            "[role='main']",
            "#main",
            "#content",
            ".article-content",
            ".post-content",
            ".entry-content",
            ".markdown-body",
            ".prose",
            ".content"
        };
        foreach (var selector in selectors)
        {
            var best = document.QuerySelectorAll(selector)
                .Where(element => NormalizeWhitespace(element.TextContent).Length >= 40)
                .OrderByDescending(element => element.TextContent.Length)
                .FirstOrDefault();
            if (best is not null)
            {
                return best;
            }
        }
        return document.Body
            ?? throw new InvalidOperationException("网页没有可读取的 body");
    }

    private static Dictionary<string, AssetSlot> CollectAssets(
        IDocument document, Uri baseUrl)
    {
        var assets = new Dictionary<string, AssetSlot>(StringComparer.Ordinal);

        void Add(string? value, string kind)
        {
            var resolved = ResolveUri(baseUrl, value);
            if (resolved is not null && !assets.ContainsKey(resolved)
                                    && assets.Count < MaxAssets)
            {
                assets[resolved] = new AssetSlot(resolved, kind);
            }
        }

        foreach (var image in document.QuerySelectorAll("img, picture source"))
        {
            Add(FirstResolvable(baseUrl,
                Attr(image, "src"),
                Attr(image, "data-src"),
                Attr(image, "data-original"),
                Attr(image, "data-lazy-src"),
                Attr(image, "data-url"),
                LargestSrcSet(Attr(image, "srcset"))), "image");
        }
        foreach (var video in document.QuerySelectorAll("video"))
        {
            Add(Attr(video, "src"), "video");
            Add(Attr(video, "poster"), "image");
        }
        foreach (var source in document.QuerySelectorAll("video source"))
        {
            Add(Attr(source, "src"), "video");
        }
        foreach (var audio in document.QuerySelectorAll("audio, audio source"))
        {
            Add(Attr(audio, "src"), "audio");
        }
        foreach (var link in document.QuerySelectorAll("a[href]"))
        {
            var resolved = ResolveUri(baseUrl, link.GetAttribute("href"));
            if (resolved is not null && HasDownloadExtension(resolved))
            {
                Add(resolved, "attachment");
            }
        }
        return assets;
    }

    private async Task<long> DownloadAssetAsync(
        AssetSlot slot,
        string assetsDirectory,
        Uri referer,
        long remainingBytes,
        CancellationToken cancellationToken)
    {
        if (IsStreamingManifest(slot.Url))
        {
            return 0;
        }
        if (slot.Url.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
        {
            return await SaveDataUrlAsync(slot, assetsDirectory, cancellationToken);
        }
        using var request = new HttpRequestMessage(HttpMethod.Get, slot.Url);
        request.Headers.Referrer = referer;
        request.Headers.Accept.ParseAdd("*/*");
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            return 0;
        }
        var mime = response.Content.Headers.ContentType?.MediaType;
        if (mime?.StartsWith("text/html", StringComparison.OrdinalIgnoreCase) == true)
        {
            return 0;
        }
        var length = response.Content.Headers.ContentLength;
        var limit = Math.Min(MaxAssetBytes, remainingBytes);
        if (length > limit || limit <= 0)
        {
            return 0;
        }
        var extension = ExtensionFor(mime, slot.Url);
        var localName = AssetHash(slot.Url) + "." + extension;
        var target = Path.Combine(assetsDirectory, localName);
        var temp = target + ".part";
        long total = 0;
        try
        {
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            await using (var output = new FileStream(
                             temp, FileMode.Create, FileAccess.Write, FileShare.None,
                             64 * 1024, FileOptions.Asynchronous))
            {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = await input.ReadAsync(buffer, cancellationToken)) != 0)
                {
                    total += read;
                    if (total > limit)
                    {
                        throw new InvalidOperationException("资源超过下载上限");
                    }
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                }
                await output.FlushAsync(cancellationToken);
            }
            File.Move(temp, target, true);
            slot.LocalName = localName;
            slot.Mime = mime;
            slot.Bytes = total;
            return total;
        }
        catch
        {
            TryDeleteFile(temp);
            return 0;
        }
    }

    private static async Task<long> SaveDataUrlAsync(
        AssetSlot slot, string assetsDirectory, CancellationToken cancellationToken)
    {
        var comma = slot.Url.IndexOf(',');
        if (comma < 0)
        {
            return 0;
        }
        var header = slot.Url[5..comma];
        var payload = slot.Url[(comma + 1)..];
        var mime = header.Split(';')[0];
        if (string.IsNullOrWhiteSpace(mime))
        {
            mime = "application/octet-stream";
        }
        byte[] bytes;
        try
        {
            bytes = header.Contains(";base64", StringComparison.OrdinalIgnoreCase)
                ? Convert.FromBase64String(payload)
                : Encoding.UTF8.GetBytes(Uri.UnescapeDataString(payload));
        }
        catch
        {
            return 0;
        }
        if (bytes.Length == 0 || bytes.Length > 8 * 1024 * 1024)
        {
            return 0;
        }
        var localName = AssetHash(slot.Url) + "." + ExtensionFor(mime, "");
        await File.WriteAllBytesAsync(
            Path.Combine(assetsDirectory, localName), bytes, cancellationToken);
        slot.LocalName = localName;
        slot.Mime = mime;
        slot.Bytes = bytes.Length;
        return bytes.Length;
    }

    private static void RenderChildren(
        INode parent,
        StringBuilder html,
        Uri baseUrl,
        IReadOnlyDictionary<string, AssetSlot> assets)
    {
        foreach (var node in parent.ChildNodes)
        {
            RenderNode(node, html, baseUrl, assets);
        }
    }

    private static void RenderNode(
        INode node,
        StringBuilder html,
        Uri baseUrl,
        IReadOnlyDictionary<string, AssetSlot> assets)
    {
        if (node is IText text)
        {
            html.Append(WebUtility.HtmlEncode(text.Data));
            return;
        }
        if (node is not IElement element)
        {
            return;
        }
        var tag = element.LocalName.ToLowerInvariant();
        if (ForbiddenTags.Contains(tag) || IsNoise(element))
        {
            return;
        }
        if (tag == "source")
        {
            return;
        }
        if (tag is "img")
        {
            var original = FirstResolvable(baseUrl,
                Attr(element, "src"),
                Attr(element, "data-src"),
                Attr(element, "data-original"),
                Attr(element, "data-lazy-src"),
                Attr(element, "data-url"),
                LargestSrcSet(Attr(element, "srcset")));
            if (original is not null && assets.TryGetValue(original, out var image)
                                    && image.LocalName is not null)
            {
                html.Append("<img src=\"assets/")
                    .Append(Attribute(image.LocalName))
                    .Append("\" alt=\"")
                    .Append(Attribute(element.GetAttribute("alt") ?? ""))
                    .Append("\" loading=\"lazy\">");
            }
            return;
        }
        if (tag is "video" or "audio")
        {
            var original = tag == "video"
                ? FirstResolvable(baseUrl,
                    Attr(element, "src"),
                    element.QuerySelector("source")?.GetAttribute("src"))
                : FirstResolvable(baseUrl,
                    Attr(element, "src"),
                    element.QuerySelector("source")?.GetAttribute("src"));
            if (original is null || !assets.TryGetValue(original, out var media)
                                 || media.LocalName is null)
            {
                return;
            }
            html.Append('<').Append(tag)
                .Append(" controls preload=\"metadata\" src=\"assets/")
                .Append(Attribute(media.LocalName)).Append('"');
            if (tag == "video")
            {
                var poster = ResolveUri(baseUrl, element.GetAttribute("poster"));
                if (poster is not null && assets.TryGetValue(poster, out var posterAsset)
                                       && posterAsset.LocalName is not null)
                {
                    html.Append(" poster=\"assets/")
                        .Append(Attribute(posterAsset.LocalName)).Append('"');
                }
            }
            html.Append("></").Append(tag).Append('>');
            return;
        }
        if (tag == "picture")
        {
            RenderChildren(element, html, baseUrl, assets);
            return;
        }
        if (!AllowedTags.Contains(tag))
        {
            RenderChildren(element, html, baseUrl, assets);
            return;
        }
        if (tag is "br" or "hr")
        {
            html.Append('<').Append(tag).Append('>');
            return;
        }

        html.Append('<').Append(tag);
        if (tag == "a")
        {
            var original = ResolveUri(baseUrl, element.GetAttribute("href"));
            string? href = null;
            if (original is not null && assets.TryGetValue(original, out var file)
                                     && file.LocalName is not null)
            {
                href = "assets/" + file.LocalName;
            }
            else
            {
                href = original;
            }
            if (href is not null)
            {
                html.Append(" href=\"").Append(Attribute(href)).Append('"');
                if (href.StartsWith("assets/", StringComparison.Ordinal))
                {
                    html.Append(" download");
                }
            }
        }
        else if (tag is "td" or "th")
        {
            AppendAttribute(html, "colspan", element.GetAttribute("colspan"));
            AppendAttribute(html, "rowspan", element.GetAttribute("rowspan"));
        }
        else if (tag == "ol")
        {
            AppendAttribute(html, "start", element.GetAttribute("start"));
        }
        else if (tag == "time")
        {
            AppendAttribute(html, "datetime", element.GetAttribute("datetime"));
        }
        var title = element.GetAttribute("title");
        if (!string.IsNullOrWhiteSpace(title))
        {
            AppendAttribute(html, "title", title);
        }
        html.Append('>');
        RenderChildren(element, html, baseUrl, assets);
        html.Append("</").Append(tag).Append('>');
    }

    private static bool IsNoise(IElement element)
    {
        if (element.HasAttribute("hidden")
            || string.Equals(element.GetAttribute("aria-hidden"), "true",
                StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }
        var tokens = Regex.Split(
                $"{element.ClassName} {element.Id}", @"[^A-Za-z0-9]+")
            .Where(token => token.Length > 0);
        return tokens.Any(token => NoiseTokens.Contains(token));
    }

    private static string BuildReaderHtml(
        string title,
        Uri pageUrl,
        string contentHtml,
        int images,
        int videos,
        int audios,
        int attachments)
    {
        var metaParts = new List<string> { $"{images} 张图片", $"{videos} 个视频" };
        if (audios > 0) metaParts.Add($"{audios} 个音频");
        if (attachments > 0) metaParts.Add($"{attachments} 个附件");
        var sourceUrl = Attribute(pageUrl.AbsoluteUri);
        var heading = WebUtility.HtmlEncode(title);
        var host = WebUtility.HtmlEncode(pageUrl.Host);
        var captured = WebUtility.HtmlEncode(DateTime.Now.ToString("yyyy-MM-dd HH:mm"));
        var meta = WebUtility.HtmlEncode(string.Join(" · ", metaParts) + " · " + captured);
        return $$"""
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta http-equiv="Content-Security-Policy" content="default-src 'self' data: blob:; img-src 'self' data: blob:; media-src 'self' data: blob:; style-src 'unsafe-inline'; script-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>{{heading}}</title>
              <style>
                :root{color-scheme:light;--ink:#24312d;--muted:#6d7974;--paper:#faf8f2;--line:#dedbd2;--accent:#e8743b;--green:#1e5b4f}
                *{box-sizing:border-box}html{background:#eeeae1}
                body{margin:0;background:var(--paper);color:var(--ink);font:18px/1.78 Georgia,"Noto Serif SC","Songti SC",serif}
                .wrap{max-width:820px;margin:0 auto;padding:42px 32px 90px}
                .eyebrow{font:700 12px/1.4 "Microsoft YaHei UI",sans-serif;letter-spacing:.16em;color:var(--accent);text-transform:uppercase}
                h1{font-size:clamp(34px,5vw,54px);line-height:1.12;letter-spacing:-.025em;margin:12px 0 18px;color:#173c34}
                .meta{padding-bottom:22px;margin-bottom:32px;border-bottom:1px solid var(--line);font:14px/1.65 "Microsoft YaHei UI",sans-serif;color:var(--muted)}
                .meta a{color:var(--green);text-decoration:none;border-bottom:1px solid #9cb6ac}
                article{overflow-wrap:anywhere}article p{margin:0 0 1.15em}
                article h1,article h2,article h3,article h4{font-family:"Microsoft YaHei UI",sans-serif;line-height:1.3;color:#173c34;margin:1.8em 0 .75em}
                a{color:#166b5b}img,video{display:block;max-width:100%;height:auto;margin:24px auto;border-radius:8px;background:#e8e4da}
                audio{display:block;width:100%;margin:20px 0}figure{margin:28px 0}
                figcaption{font:13px/1.5 "Microsoft YaHei UI",sans-serif;color:var(--muted);text-align:center;margin-top:-14px}
                blockquote{margin:26px 0;padding:4px 0 4px 22px;border-left:4px solid var(--accent);color:#4d5d57;font-style:italic}
                pre{overflow:auto;padding:18px;background:#e9eee9;border-radius:8px;font:14px/1.55 Consolas,monospace}
                code{font-family:Consolas,monospace;font-size:.9em}table{width:100%;border-collapse:collapse;font-size:15px;margin:24px 0}
                th,td{border:1px solid var(--line);padding:9px;text-align:left}
                .footer{margin-top:58px;padding-top:20px;border-top:1px solid var(--line);font:12px/1.7 "Microsoft YaHei UI",sans-serif;color:var(--muted)}
              </style>
            </head>
            <body>
              <main class="wrap">
                <div class="eyebrow">PAGEVAULT · 本地保存</div>
                <h1>{{heading}}</h1>
                <div class="meta"><a href="{{sourceUrl}}" rel="noreferrer">{{host}}</a><br>{{meta}}</div>
                <article>{{contentHtml}}</article>
                <div class="footer">此副本由拾页保存。远端脚本和表单已移除；请在遵守网站条款与版权要求的前提下使用。</div>
              </main>
            </body>
            </html>
            """;
    }

    private static string? FirstResolvable(Uri baseUrl, params string?[] candidates)
    {
        foreach (var candidate in candidates)
        {
            var resolved = ResolveUri(baseUrl, candidate);
            if (resolved is not null)
            {
                return resolved;
            }
        }
        return null;
    }

    private static string? ResolveUri(Uri baseUrl, string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }
        value = value.Trim();
        if (value.StartsWith('#'))
        {
            return null;
        }
        if (value.StartsWith("data:", StringComparison.OrdinalIgnoreCase))
        {
            return value;
        }
        if (!Uri.TryCreate(baseUrl, value, out var resolved))
        {
            return null;
        }
        if (resolved.Scheme != Uri.UriSchemeHttp
            && resolved.Scheme != Uri.UriSchemeHttps
            && resolved.Scheme != Uri.UriSchemeMailto)
        {
            return null;
        }
        if (resolved.Scheme != Uri.UriSchemeMailto)
        {
            var builder = new UriBuilder(resolved) { Fragment = "" };
            return builder.Uri.AbsoluteUri;
        }
        return resolved.AbsoluteUri;
    }

    private static string? Attr(IElement element, string name) =>
        element.HasAttribute(name) ? element.GetAttribute(name) : null;

    private static string? LargestSrcSet(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }
        return value.Split(',', StringSplitOptions.RemoveEmptyEntries)
            .LastOrDefault()?.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault();
    }

    private static bool HasDownloadExtension(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return false;
        }
        var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
        return DownloadExtensions.Contains(extension);
    }

    private static bool IsStreamingManifest(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return false;
        }
        return uri.AbsolutePath.EndsWith(".m3u8", StringComparison.OrdinalIgnoreCase)
               || uri.AbsolutePath.EndsWith(".mpd", StringComparison.OrdinalIgnoreCase);
    }

    private static string ExtensionFor(string? mime, string url)
    {
        var extension = (mime ?? "").Split(';')[0].Trim().ToLowerInvariant() switch
        {
            "image/jpeg" => "jpg",
            "image/png" => "png",
            "image/gif" => "gif",
            "image/webp" => "webp",
            "image/avif" => "avif",
            "image/svg+xml" => "svg",
            "video/mp4" => "mp4",
            "video/webm" => "webm",
            "video/quicktime" => "mov",
            "audio/mpeg" => "mp3",
            "audio/mp4" => "m4a",
            "audio/wav" or "audio/x-wav" => "wav",
            "audio/ogg" => "ogg",
            "audio/flac" => "flac",
            "application/pdf" => "pdf",
            "application/zip" => "zip",
            _ => ""
        };
        if (!string.IsNullOrWhiteSpace(extension))
        {
            return extension;
        }
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var candidate = Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant();
            if (Regex.IsMatch(candidate, "^[a-z0-9]{1,8}$"))
            {
                return candidate;
            }
        }
        return "bin";
    }

    private static string AssetHash(string value)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(hash.AsSpan(0, 8)).ToLowerInvariant();
    }

    private static int DownloadedCount(
        IReadOnlyDictionary<string, AssetSlot> assets, string kind) =>
        assets.Values.Count(asset =>
            asset.Kind == kind && asset.LocalName is not null);

    private static async Task<byte[]> ReadLimitedAsync(
        Stream stream, int limit, CancellationToken cancellationToken)
    {
        using var output = new MemoryStream();
        var buffer = new byte[32 * 1024];
        var total = 0;
        int read;
        while ((read = await stream.ReadAsync(buffer, cancellationToken)) != 0)
        {
            total += read;
            if (total > limit)
            {
                throw new InvalidOperationException("网页内容超过大小限制");
            }
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
        }
        return output.ToArray();
    }

    private static void AppendAttribute(StringBuilder html, string name, string? value)
    {
        if (!string.IsNullOrWhiteSpace(value))
        {
            html.Append(' ').Append(name).Append("=\"").Append(Attribute(value)).Append('"');
        }
    }

    private static string Attribute(string value) => WebUtility.HtmlEncode(value);

    private static string NormalizeWhitespace(string value) =>
        Regex.Replace(value, @"\s+", " ").Trim();

    private static string Abbreviate(string value, int maxLength) =>
        value.Length <= maxLength ? value : value[..(maxLength - 1)] + "…";

    private static string RandomSuffix() =>
        Convert.ToHexString(RandomNumberGenerator.GetBytes(3)).ToLowerInvariant();

    private static void Report(IProgress<ScrapeProgress>? progress, int percent, string message) =>
        progress?.Report(new ScrapeProgress(percent, message));

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, true);
            }
        }
        catch
        {
            // Best-effort cleanup only.
        }
    }

    private static void TryDeleteFile(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch
        {
            // Best-effort cleanup only.
        }
    }

    private sealed class FetchedPage(Uri url, string html)
    {
        public Uri Url { get; } = url;
        public string Html { get; } = html;
    }

    private sealed class AssetSlot(string url, string kind)
    {
        public string Url { get; } = url;
        public string Kind { get; } = kind;
        public string? LocalName { get; set; }
        public string? Mime { get; set; }
        public long Bytes { get; set; }
    }
}
