using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PageVault;

public sealed class ResolvedVideo
{
    public required string Title { get; init; }
    public required string SourceUrl { get; init; }
    public required string Platform { get; init; }
    public string? Author { get; init; }
    public string? Description { get; init; }
    public required IReadOnlyList<ResolvedMedia> Media { get; init; }
}

public sealed class ResolvedMedia
{
    public required string Url { get; init; }
    public required string Kind { get; init; }
    public IReadOnlyDictionary<string, string> Headers { get; init; } =
        new Dictionary<string, string>();
}

public sealed class VideoResolver
{
    private const string IosUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 " +
        "Mobile/15E148 Safari/604.1 Edg/132.0.0.0";

    private static readonly Regex BilibiliBvRegex =
        new(@"(?<![A-Za-z0-9])(?<id>BV[0-9A-Za-z]{10})", RegexOptions.Compiled);
    private static readonly Regex BilibiliAvRegex =
        new(@"(?<![A-Za-z0-9])av(?<id>\d{6,})", RegexOptions.Compiled |
                                                    RegexOptions.IgnoreCase);
    private static readonly Regex DouyinIdRegex =
        new(@"(?:video|note|slides|share/video|share/note|share/slides)/" +
            @"(?<id>\d{10,})|aweme_id[=:/\s]+(?<id2>\d{10,})",
            RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private static readonly HashSet<string> YtDlpHosts = new(
        new[]
        {
            "youtube.com", "youtu.be", "tiktok.com", "instagram.com",
            "vimeo.com", "dailymotion.com", "twitch.tv", "facebook.com",
            "x.com", "twitter.com", "reddit.com", "soundcloud.com",
            "acfun.cn", "bilibili.com", "b23.tv", "douyin.com",
            "iesdouyin.com", "kuaishou.com", "xiaohongshu.com"
        },
        StringComparer.OrdinalIgnoreCase);

    private readonly HttpClient _http;
    private readonly CookieContainer _cookies = new();

    public VideoResolver()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            MaxAutomaticRedirections = 8,
            AutomaticDecompression = DecompressionMethods.GZip
                                    | DecompressionMethods.Deflate
                                    | DecompressionMethods.Brotli,
            CookieContainer = _cookies,
            UseCookies = true
        };
        _http = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(3) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent.Working);
        _http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9,en;q=0.7");
    }

    public async Task<ResolvedVideo?> ResolveAsync(
        string inputUrl,
        IProgress<ScrapeProgress>? progress,
        CancellationToken cancellationToken)
    {
        var uri = new Uri(ScraperService.NormalizeUrl(inputUrl));
        var host = uri.Host.ToLowerInvariant();
        var nativeErrors = new List<string>();

        if (IsHost(host, "bilibili.com") || IsHost(host, "b23.tv")
                                             || IsHost(host, "bili2233.cn"))
        {
            progress?.Report(new ScrapeProgress(4, "正在解析 B 站视频"));
            try
            {
                return await ResolveBilibiliAsync(uri, cancellationToken);
            }
            catch (Exception error)
            {
                nativeErrors.Add("B站原生解析：" + error.Message);
            }
        }
        else if (IsHost(host, "douyin.com") || IsHost(host, "iesdouyin.com"))
        {
            progress?.Report(new ScrapeProgress(4, "正在解析抖音视频"));
            try
            {
                return await ResolveDouyinAsync(uri, cancellationToken);
            }
            catch (Exception error)
            {
                nativeErrors.Add("抖音原生解析：" + error.Message);
            }
        }
        else if (IsHost(host, "kuaishou.com")
                 || IsHost(host, "chenzhongtech.com"))
        {
            progress?.Report(new ScrapeProgress(4, "正在解析快手视频"));
            try
            {
                return await ResolveKuaishouAsync(uri, cancellationToken);
            }
            catch (Exception error)
            {
                nativeErrors.Add("快手原生解析：" + error.Message);
            }
        }
        else if (IsHost(host, "xiaohongshu.com")
                 || IsHost(host, "xhslink.com") || IsHost(host, "xhslink.cn"))
        {
            progress?.Report(new ScrapeProgress(4, "正在解析小红书笔记"));
            try
            {
                return await ResolveXiaohongshuAsync(uri, cancellationToken);
            }
            catch (Exception error)
            {
                nativeErrors.Add("小红书原生解析：" + error.Message);
            }
        }

        if (YtDlpHosts.Any(candidate => IsHost(host, candidate)))
        {
            progress?.Report(new ScrapeProgress(7, "正在通过通用解析器提取媒体"));
            try
            {
                return await ResolveWithYtDlpAsync(uri, cancellationToken);
            }
            catch (Exception error)
            {
                nativeErrors.Add("yt-dlp：" + error.Message);
            }
        }

        if (nativeErrors.Count > 0)
        {
            throw new InvalidOperationException(
                "视频解析失败。" + string.Join("；", nativeErrors));
        }
        return null;
    }

    private async Task<ResolvedVideo> ResolveBilibiliAsync(
        Uri inputUri, CancellationToken cancellationToken)
    {
        var finalUri = inputUri;
        var textForIds = inputUri.AbsoluteUri;
        if (IsHost(inputUri.Host, "b23.tv") || IsHost(inputUri.Host, "bili2233.cn"))
        {
            finalUri = await FollowRedirectAsync(inputUri, cancellationToken);
            textForIds = finalUri.AbsoluteUri;
        }

        string bvid;
        var bv = BilibiliBvRegex.Match(textForIds);
        if (bv.Success)
        {
            bvid = bv.Groups["id"].Value;
        }
        else
        {
            var av = BilibiliAvRegex.Match(textForIds);
            if (!av.Success)
            {
                throw new InvalidOperationException("链接中没有 BV/av 视频编号");
            }
            bvid = "av" + av.Groups["id"].Value;
        }

        var pageNumber = 1;
        var pageMatch = Regex.Match(inputUri.Query, @"(?:[?&])p=(\d{1,3})");
        if (pageMatch.Success)
        {
            pageNumber = Math.Max(1, int.Parse(pageMatch.Groups[1].Value));
        }

        var headers = new Dictionary<string, string>
        {
            ["Referer"] = "https://www.bilibili.com/",
            ["Origin"] = "https://www.bilibili.com",
            ["User-Agent"] = UserAgent.Working
        };
        var apiKey = bvid.StartsWith("av", StringComparison.OrdinalIgnoreCase)
            ? "aid"
            : "bvid";
        var viewUrl = new Uri(
            $"https://api.bilibili.com/x/web-interface/view?{apiKey}={Uri.EscapeDataString(bvid)}");
        using var view = JsonDocument.Parse(
            await GetStringAsync(viewUrl, headers, cancellationToken));
        var root = view.RootElement;
        if (ReadInt(root, "code") != 0)
        {
            throw new InvalidOperationException(ReadString(root, "message") ?? "视频信息接口失败");
        }
        var data = root.GetProperty("data");
        var canonicalBvid = ReadString(data, "bvid") ?? bvid;
        var cid = ReadLong(data, "cid");
        if (data.TryGetProperty("pages", out var pages)
            && pages.ValueKind == JsonValueKind.Array && pages.GetArrayLength() > 0)
        {
            var index = Math.Min(pageNumber - 1, pages.GetArrayLength() - 1);
            cid = ReadLong(pages[index], "cid");
        }
        if (cid <= 0)
        {
            throw new InvalidOperationException("未获取到视频分集 CID");
        }

        var playUrl = new Uri(
            $"https://api.bilibili.com/x/player/playurl?bvid={Uri.EscapeDataString(canonicalBvid)}"
            + $"&cid={cid}&qn=64&fnval=0&fnver=0&fourk=0");
        using var play = JsonDocument.Parse(
            await GetStringAsync(playUrl, headers, cancellationToken));
        var playRoot = play.RootElement;
        if (ReadInt(playRoot, "code") != 0
            || !playRoot.TryGetProperty("data", out var playData)
            || !playData.TryGetProperty("durl", out var durl)
            || durl.ValueKind != JsonValueKind.Array
            || durl.GetArrayLength() == 0)
        {
            throw new InvalidOperationException(
                ReadString(playRoot, "message") ?? "未找到 B 站直接视频流");
        }
        if (durl.GetArrayLength() > 1)
        {
            throw new InvalidOperationException("该视频被拆分为多个媒体分片，当前版本暂不支持合并");
        }
        var videoUrl = NormalizeMediaUrl(ReadString(durl[0], "url"));
        if (videoUrl is null)
        {
            throw new InvalidOperationException("B 站没有返回可下载视频地址");
        }

        var media = new List<ResolvedMedia>();
        var thumbnail = NormalizeMediaUrl(ReadString(data, "pic"));
        if (thumbnail is not null)
        {
            media.Add(new ResolvedMedia
            {
                Url = thumbnail,
                Kind = "image",
                Headers = headers
            });
        }
        media.Add(new ResolvedMedia
        {
            Url = videoUrl,
            Kind = "video",
            Headers = headers
        });

        var title = ReadString(data, "title") ?? $"B站视频 {canonicalBvid}";
        if (data.TryGetProperty("pages", out var pageList)
            && pageList.ValueKind == JsonValueKind.Array
            && pageList.GetArrayLength() > 1)
        {
            var index = Math.Min(pageNumber - 1, pageList.GetArrayLength() - 1);
            title += "｜" + (ReadString(pageList[index], "part") ?? $"P{pageNumber}");
        }

        return new ResolvedVideo
        {
            Title = title,
            SourceUrl = $"https://www.bilibili.com/video/{canonicalBvid}"
                         + (pageNumber > 1 ? $"?p={pageNumber}" : ""),
            Platform = "哔哩哔哩",
            Author = data.TryGetProperty("owner", out var owner)
                ? ReadString(owner, "name")
                : null,
            Description = ReadString(data, "desc"),
            Media = media
        };
    }

    private async Task<ResolvedVideo> ResolveDouyinAsync(
        Uri inputUri, CancellationToken cancellationToken)
    {
        var finalUri = await FollowRedirectAsync(inputUri, cancellationToken);
        var idMatch = DouyinIdRegex.Match(finalUri.AbsoluteUri);
        var videoId = idMatch.Groups["id"].Success
            ? idMatch.Groups["id"].Value
            : idMatch.Groups["id2"].Value;
        if (string.IsNullOrWhiteSpace(videoId))
        {
            throw new InvalidOperationException("无法从分享链接识别作品 ID");
        }

        var shareUrl = new Uri($"https://www.iesdouyin.com/share/video/{videoId}/");
        var headers = new Dictionary<string, string>
        {
            ["Referer"] = "https://www.iesdouyin.com/",
            ["User-Agent"] = IosUserAgent
        };

        string html = "";
        Exception? lastError = null;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            try
            {
                if (attempt > 0 || !await HasTtwidAsync())
                {
                    await RegisterTtwidAsync(headers, cancellationToken);
                }
                html = await GetStringAsync(shareUrl, headers, cancellationToken);
                if (html.Contains("_ROUTER_DATA", StringComparison.Ordinal))
                {
                    break;
                }
            }
            catch (Exception error)
            {
                lastError = error;
            }
        }
        if (string.IsNullOrWhiteSpace(html))
        {
            throw new InvalidOperationException(
                "抖音分享页读取失败" + (lastError is null ? "" : "：" + lastError.Message));
        }

        var match = Regex.Match(html, @"window\._ROUTER_DATA\s*=\s*(.*?)</script>",
            RegexOptions.Singleline);
        if (!match.Success)
        {
            throw new InvalidOperationException("抖音页面中没有路由数据");
        }
        using var json = JsonDocument.Parse(match.Groups[1].Value.Trim());
        if (!json.RootElement.TryGetProperty("loaderData", out var loaderData)
            || !TryFindDouyinItem(loaderData, out var item))
        {
            throw new InvalidOperationException("抖音作品不存在、已删除或需要登录");
        }

        var media = new List<ResolvedMedia>();
        var mediaHeaders = new Dictionary<string, string>
        {
            ["Referer"] = shareUrl.AbsoluteUri,
            ["User-Agent"] = IosUserAgent
        };
        if (item.TryGetProperty("video", out var video)
            && video.ValueKind == JsonValueKind.Object
            && TryFirstUrl(video, "play_addr", out var videoUrl))
        {
            videoUrl = NormalizeMediaUrl(videoUrl)?.Replace("playwm", "play",
                StringComparison.OrdinalIgnoreCase);
            if (videoUrl is not null)
            {
                media.Add(new ResolvedMedia
                {
                    Url = videoUrl,
                    Kind = "video",
                    Headers = mediaHeaders
                });
            }
            if (TryFirstUrl(video, "cover", out var cover) && cover is not null)
            {
                media.Add(new ResolvedMedia
                {
                    Url = cover,
                    Kind = "image",
                    Headers = mediaHeaders
                });
            }
        }
        if (item.TryGetProperty("images", out var images)
            && images.ValueKind == JsonValueKind.Array)
        {
            foreach (var image in images.EnumerateArray())
            {
                if (image.TryGetProperty("url_list", out var urls)
                    && urls.ValueKind == JsonValueKind.Array)
                {
                    var url = NormalizeMediaUrl(
                        urls.EnumerateArray().Select(ReadStringValue)
                            .FirstOrDefault(value => !string.IsNullOrWhiteSpace(value)));
                    if (url is not null)
                    {
                        media.Add(new ResolvedMedia
                        {
                            Url = url,
                            Kind = "image",
                            Headers = mediaHeaders
                        });
                    }
                }
            }
        }
        if (media.Count == 0)
        {
            throw new InvalidOperationException("抖音作品没有可保存的视频或图片");
        }

        var duration = item.TryGetProperty("video", out var videoData)
                       && videoData.ValueKind == JsonValueKind.Object
            ? ReadLong(videoData, "duration")
            : 0;
        if (duration > 10_000)
        {
            duration /= 1000;
        }
        return new ResolvedVideo
        {
            Title = Abbreviate(ReadString(item, "desc") ?? $"抖音作品 {videoId}", 180),
            SourceUrl = shareUrl.AbsoluteUri,
            Platform = "抖音",
            Author = item.TryGetProperty("author", out var author)
                ? ReadString(author, "nickname")
                : null,
            Description = duration > 0 ? $"视频时长：{duration} 秒" : null,
            Media = media
        };
    }

    private async Task<ResolvedVideo> ResolveKuaishouAsync(
        Uri inputUri, CancellationToken cancellationToken)
    {
        var headers = new Dictionary<string, string>
        {
            ["Referer"] = "https://v.kuaishou.com/",
            ["User-Agent"] = IosUserAgent
        };
        var page = await GetPageAsync(inputUri, headers, cancellationToken);
        var pageUrl = page.Url.AbsoluteUri.Replace("/fw/long-video/", "/fw/photo/");
        if (pageUrl != page.Url.AbsoluteUri)
        {
            page = await GetPageAsync(new Uri(pageUrl), headers, cancellationToken);
        }
        var match = Regex.Match(page.Html,
            @"window\.INIT_STATE\s*=\s*(.*?)</script>", RegexOptions.Singleline);
        if (!match.Success)
        {
            throw new InvalidOperationException("快手页面中没有作品数据");
        }
        using var json = JsonDocument.Parse(match.Groups[1].Value.Trim());
        if (!TryFindProperty(json.RootElement, "photo", out var photo)
            || photo.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidOperationException("快手作品不存在或需要登录");
        }

        var mediaHeaders = new Dictionary<string, string>
        {
            ["Referer"] = page.Url.AbsoluteUri,
            ["User-Agent"] = IosUserAgent
        };
        var media = new List<ResolvedMedia>();
        var videoUrl = FirstObjectUrl(photo, "mainMvUrls");
        if (videoUrl is not null)
        {
            media.Add(new ResolvedMedia
            {
                Url = videoUrl,
                Kind = "video",
                Headers = mediaHeaders
            });
        }
        var coverUrl = FirstObjectUrl(photo, "coverUrls");
        if (coverUrl is not null)
        {
            media.Add(new ResolvedMedia
            {
                Url = coverUrl,
                Kind = "image",
                Headers = mediaHeaders
            });
        }
        if (media.Count == 0)
        {
            throw new InvalidOperationException("快手作品没有可保存的视频");
        }
        var duration = ReadLong(photo, "duration");
        if (duration > 10_000)
        {
            duration /= 1000;
        }
        return new ResolvedVideo
        {
            Title = Abbreviate(ReadString(photo, "caption") ?? "快手作品", 180),
            SourceUrl = page.Url.AbsoluteUri,
            Platform = "快手",
            Author = ReadString(photo, "userName"),
            Description = duration > 0 ? $"视频时长：{duration} 秒" : null,
            Media = media
        };
    }

    private async Task<ResolvedVideo> ResolveXiaohongshuAsync(
        Uri inputUri, CancellationToken cancellationToken)
    {
        var headers = new Dictionary<string, string>
        {
            ["Referer"] = "https://www.xiaohongshu.com/",
            ["User-Agent"] = UserAgent.Working,
            ["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        };
        var page = await GetPageAsync(inputUri, headers, cancellationToken);
        var match = Regex.Match(page.Html,
            @"window\.__INITIAL_STATE__=(.*?)</script>", RegexOptions.Singleline);
        if (!match.Success)
        {
            throw new InvalidOperationException("小红书页面中没有笔记数据");
        }
        using var json = JsonDocument.Parse(match.Groups[1].Value
            .Replace("undefined", "null", StringComparison.Ordinal));
        if (!TryFindProperty(json.RootElement, "noteDetailMap", out var detailMap)
            || detailMap.ValueKind != JsonValueKind.Object)
        {
            throw new InvalidOperationException("小红书分享链接失效、受限或需要登录");
        }
        var noteHolder = detailMap.EnumerateObject().Select(property => property.Value)
            .FirstOrDefault(value => value.ValueKind == JsonValueKind.Object
                                     && value.TryGetProperty("note", out _));
        if (noteHolder.ValueKind == JsonValueKind.Undefined
            || !noteHolder.TryGetProperty("note", out var note))
        {
            throw new InvalidOperationException("小红书笔记内容不存在或已删除");
        }

        var mediaHeaders = new Dictionary<string, string>
        {
            ["Referer"] = page.Url.AbsoluteUri,
            ["User-Agent"] = UserAgent.Working
        };
        var media = new List<ResolvedMedia>();
        var videoUrl = FirstXiaohongshuVideoUrl(note);
        if (videoUrl is not null)
        {
            media.Add(new ResolvedMedia
            {
                Url = videoUrl,
                Kind = "video",
                Headers = mediaHeaders
            });
        }
        if (note.TryGetProperty("imageList", out var images)
            && images.ValueKind == JsonValueKind.Array)
        {
            foreach (var image in images.EnumerateArray())
            {
                var url = ReadString(image, "urlDefault")
                          ?? ReadString(image, "urlPre")
                          ?? ReadString(image, "url");
                url = NormalizeMediaUrl(url);
                if (url is not null)
                {
                    media.Add(new ResolvedMedia
                    {
                        Url = url,
                        Kind = "image",
                        Headers = mediaHeaders
                    });
                }
            }
        }
        if (media.Count == 0)
        {
            throw new InvalidOperationException("小红书笔记没有可保存的媒体");
        }
        var author = note.TryGetProperty("user", out var user)
            ? ReadString(user, "nickname") ?? ReadString(user, "nickName")
            : null;
        return new ResolvedVideo
        {
            Title = ReadString(note, "title") ?? "小红书笔记",
            SourceUrl = page.Url.AbsoluteUri,
            Platform = "小红书",
            Author = author,
            Description = ReadString(note, "desc"),
            Media = media
        };
    }

    private async Task<ResolvedVideo> ResolveWithYtDlpAsync(
        Uri inputUri, CancellationToken cancellationToken)
    {
        var binary = EnsureYtDlpBinary();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromMinutes(2));
        var startInfo = new ProcessStartInfo
        {
            FileName = binary,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };
        foreach (var argument in new[]
                 {
                     "--no-warnings", "--no-playlist", "--skip-download",
                     "--dump-single-json", "--socket-timeout", "20",
                     "--retries", "2", inputUri.AbsoluteUri
                 })
        {
            startInfo.ArgumentList.Add(argument);
        }

        using var process = Process.Start(startInfo)
                            ?? throw new InvalidOperationException("无法启动 yt-dlp");
        var stdoutTask = process.StandardOutput.ReadToEndAsync(timeout.Token);
        var stderrTask = process.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await process.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            try
            {
                process.Kill(true);
            }
            catch
            {
                // Process may already have exited.
            }
            throw new InvalidOperationException(
                cancellationToken.IsCancellationRequested ? "解析已取消" : "通用解析器超时");
        }
        var stdout = await stdoutTask;
        var stderr = await stderrTask;
        if (process.ExitCode != 0 || string.IsNullOrWhiteSpace(stdout))
        {
            var message = stderr.Split('\n', StringSplitOptions.RemoveEmptyEntries)
                .LastOrDefault()?.Trim() ?? "没有返回媒体信息";
            throw new InvalidOperationException(CleanYtDlpError(message));
        }

        using var json = JsonDocument.Parse(stdout);
        var root = json.RootElement;
        if (root.TryGetProperty("_type", out var type)
            && ReadStringValue(type) is "playlist")
        {
            throw new InvalidOperationException("播放列表解析不在当前版本范围内");
        }
        if (!TrySelectYtDlpFormat(root, out var format, out var directUrl))
        {
            throw new InvalidOperationException(
                "该站点只提供分离音视频或不支持的流格式，当前版本无法直接保存");
        }

        var headers = ReadHeaders(format)
                      ?? ReadHeaders(root)
                      ?? new Dictionary<string, string>();
        var thumbnail = NormalizeMediaUrl(ReadString(root, "thumbnail"));
        var media = new List<ResolvedMedia>();
        if (thumbnail is not null)
        {
            media.Add(new ResolvedMedia
            {
                Url = thumbnail,
                Kind = "image",
                Headers = headers
            });
        }
        media.Add(new ResolvedMedia
        {
            Url = directUrl,
            Kind = "video",
            Headers = headers
        });

        var duration = ReadDouble(root, "duration");
        var extractor = ReadString(root, "extractor_key")
                        ?? ReadString(root, "extractor")
                        ?? new Uri(ReadString(root, "webpage_url") ?? inputUri.AbsoluteUri).Host;
        return new ResolvedVideo
        {
            Title = ReadString(root, "title") ?? "解析视频",
            SourceUrl = ReadString(root, "webpage_url") ?? inputUri.AbsoluteUri,
            Platform = extractor,
            Author = ReadString(root, "uploader")
                     ?? ReadString(root, "channel")
                     ?? ReadString(root, "creator"),
            Description = duration > 0
                ? $"视频时长：{TimeSpan.FromSeconds(duration):mm\\:ss}"
                : ReadString(root, "description"),
            Media = media
        };
    }

    private async Task RegisterTtwidAsync(
        IReadOnlyDictionary<string, string> baseHeaders,
        CancellationToken cancellationToken)
    {
        var payload = JsonSerializer.Serialize(new
        {
            region = "cn",
            aid = 1768,
            needFid = false,
            service = "www.iesdouyin.com",
            union = true,
            fid = ""
        });
        using var request = new HttpRequestMessage(
            HttpMethod.Post, "https://ttwid.bytedance.com/ttwid/union/register/");
        ApplyHeaders(request, baseHeaders);
        request.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseContentRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        if (json.RootElement.TryGetProperty("redirect_url", out var redirect)
            && ReadStringValue(redirect) is { } callbackUrl)
        {
            using var callback = new HttpRequestMessage(HttpMethod.Get, callbackUrl);
            ApplyHeaders(callback, baseHeaders);
            using var callbackResponse = await _http.SendAsync(
                callback, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            // Following the callback stores the anonymous ttwid cookie in the handler.
        }
    }

    private async Task<bool> HasTtwidAsync()
    {
        var test = new Uri("https://www.iesdouyin.com/");
        return await Task.FromResult(_cookies.GetCookies(test).Count > 0);
    }

    private async Task<Uri> FollowRedirectAsync(Uri uri, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        ApplyHeaders(request, new Dictionary<string, string>
        {
            ["User-Agent"] = IosUserAgent,
            ["Referer"] = uri.GetLeftPart(UriPartial.Authority) + "/"
        });
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        return response.RequestMessage?.RequestUri ?? uri;
    }

    private async Task<string> GetStringAsync(
        Uri uri,
        IReadOnlyDictionary<string, string> headers,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        ApplyHeaders(request, headers);
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"HTTP {(int)response.StatusCode}");
        }
        return await response.Content.ReadAsStringAsync(cancellationToken);
    }

    private async Task<FetchedPage> GetPageAsync(
        Uri uri,
        IReadOnlyDictionary<string, string> headers,
        CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        ApplyHeaders(request, headers);
        using var response = await _http.SendAsync(
            request, HttpCompletionOption.ResponseContentRead, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"HTTP {(int)response.StatusCode}");
        }
        return new FetchedPage(
            response.RequestMessage?.RequestUri ?? uri,
            await response.Content.ReadAsStringAsync(cancellationToken));
    }

    private static void ApplyHeaders(
        HttpRequestMessage request,
        IReadOnlyDictionary<string, string> headers)
    {
        foreach (var (name, value) in headers)
        {
            if (string.Equals(name, "Referer", StringComparison.OrdinalIgnoreCase))
            {
                if (Uri.TryCreate(value, UriKind.Absolute, out var referer))
                {
                    request.Headers.Referrer = referer;
                }
            }
            else if (string.Equals(name, "User-Agent", StringComparison.OrdinalIgnoreCase))
            {
                request.Headers.TryAddWithoutValidation(name, value);
            }
            else
            {
                request.Headers.TryAddWithoutValidation(name, value);
            }
        }
    }

    private static bool TryFindDouyinItem(JsonElement loaderData, out JsonElement item)
    {
        item = default;
        if (loaderData.ValueKind != JsonValueKind.Object)
        {
            return false;
        }
        foreach (var page in loaderData.EnumerateObject())
        {
            if (!page.Name.Contains("/page", StringComparison.Ordinal)
                || !page.Value.TryGetProperty("videoInfoRes", out var info)
                || !info.TryGetProperty("item_list", out var items)
                || items.ValueKind != JsonValueKind.Array
                || items.GetArrayLength() == 0)
            {
                continue;
            }
            item = items[0];
            return true;
        }
        return false;
    }

    private static bool TryFirstUrl(JsonElement parent, string property, out string? url)
    {
        url = null;
        if (!parent.TryGetProperty(property, out var element)
            || element.ValueKind != JsonValueKind.Object
            || !element.TryGetProperty("url_list", out var list)
            || list.ValueKind != JsonValueKind.Array)
        {
            return false;
        }
        url = list.EnumerateArray().Select(ReadStringValue)
            .FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));
        return url is not null;
    }

    private static string? FirstObjectUrl(JsonElement parent, string property)
    {
        if (!parent.TryGetProperty(property, out var list)
            || list.ValueKind != JsonValueKind.Array
            || list.GetArrayLength() == 0)
        {
            return null;
        }
        return NormalizeMediaUrl(ReadString(list[0], "url"));
    }

    private static string? FirstXiaohongshuVideoUrl(JsonElement note)
    {
        if (!note.TryGetProperty("video", out var video)
            || !video.TryGetProperty("media", out var media)
            || !media.TryGetProperty("stream", out var stream)
            || stream.ValueKind != JsonValueKind.Object)
        {
            return null;
        }
        foreach (var codec in new[] { "h265", "h264", "av1", "h266" })
        {
            if (stream.TryGetProperty(codec, out var variants)
                && variants.ValueKind == JsonValueKind.Array
                && variants.GetArrayLength() > 0)
            {
                var url = ReadString(variants[0], "masterUrl")
                          ?? ReadString(variants[0], "backupUrls");
                if (!string.IsNullOrWhiteSpace(url))
                {
                    return NormalizeMediaUrl(url);
                }
            }
        }
        return null;
    }

    private static bool TryFindProperty(
        JsonElement element, string propertyName, out JsonElement result, int depth = 0)
    {
        result = default;
        if (depth > 12)
        {
            return false;
        }
        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in element.EnumerateObject())
            {
                if (property.NameEquals(propertyName))
                {
                    result = property.Value;
                    return true;
                }
                if (TryFindProperty(property.Value, propertyName, out result, depth + 1))
                {
                    return true;
                }
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in element.EnumerateArray())
            {
                if (TryFindProperty(child, propertyName, out result, depth + 1))
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static bool TrySelectYtDlpFormat(
        JsonElement root, out JsonElement selected, out string directUrl)
    {
        selected = default;
        directUrl = "";
        var candidates = new List<(JsonElement Element, string Url, double Score)>();
        if (root.TryGetProperty("formats", out var formats)
            && formats.ValueKind == JsonValueKind.Array)
        {
            foreach (var format in formats.EnumerateArray())
            {
                if (!IsCombinedHttpFormat(format, out var url))
                {
                    continue;
                }
                var height = ReadDouble(format, "height");
                var size = ReadDouble(format, "filesize")
                           + ReadDouble(format, "filesize_approx");
                candidates.Add((format, url, height * 10_000 + size / 1024));
            }
        }
        if (candidates.Count == 0 && IsCombinedHttpFormat(root, out var topUrl))
        {
            candidates.Add((root, topUrl, ReadDouble(root, "height") * 10_000));
        }
        if (candidates.Count == 0)
        {
            return false;
        }
        var best = candidates.OrderByDescending(candidate => candidate.Score).First();
        selected = best.Element;
        directUrl = best.Url;
        return true;
    }

    private static bool IsCombinedHttpFormat(JsonElement format, out string url)
    {
        url = "";
        var candidate = ReadString(format, "url");
        var protocol = ReadString(format, "protocol") ?? "";
        var vcodec = ReadString(format, "vcodec") ?? "none";
        var acodec = ReadString(format, "acodec") ?? "none";
        var ext = ReadString(format, "ext") ?? "";
        if (string.IsNullOrWhiteSpace(candidate)
            || !candidate.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
            || protocol.Contains("m3u8", StringComparison.OrdinalIgnoreCase)
            || protocol.Contains("dash", StringComparison.OrdinalIgnoreCase)
            || vcodec == "none" || acodec == "none"
            || ext is not ("mp4" or "webm" or "mov"))
        {
            return false;
        }
        url = candidate;
        return true;
    }

    private static Dictionary<string, string>? ReadHeaders(JsonElement element)
    {
        if (!element.TryGetProperty("http_headers", out var headers)
            || headers.ValueKind != JsonValueKind.Object)
        {
            return null;
        }
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var header in headers.EnumerateObject())
        {
            if (ReadStringValue(header.Value) is { } value)
            {
                result[header.Name] = value;
            }
        }
        return result.Count == 0 ? null : result;
    }

    private static string EnsureYtDlpBinary()
    {
        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PageVault", "tools");
        Directory.CreateDirectory(directory);
        var target = Path.Combine(directory, "yt-dlp.exe");
        const string resourceName = "PageVault.yt-dlp.exe";
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(resourceName);
        if (stream is null)
        {
            var adjacent = Path.Combine(AppContext.BaseDirectory, "yt-dlp.exe");
            if (File.Exists(adjacent))
            {
                return adjacent;
            }
            throw new InvalidOperationException(
                "当前安装包未包含 yt-dlp，请使用完整 Release 包");
        }
        if (File.Exists(target) && new FileInfo(target).Length == stream.Length)
        {
            return target;
        }
        var temp = target + ".part";
        using (var output = File.Create(temp))
        {
            stream.CopyTo(output);
        }
        File.Move(temp, target, true);
        return target;
    }

    private static string? NormalizeMediaUrl(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)
            || !Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            return null;
        }
        if (uri.Scheme == Uri.UriSchemeHttp)
        {
            uri = new UriBuilder(uri) { Scheme = Uri.UriSchemeHttps, Port = -1 }.Uri;
        }
        return uri.AbsoluteUri;
    }

    private static string? ReadString(JsonElement element, string property)
    {
        return element.ValueKind == JsonValueKind.Object
               && element.TryGetProperty(property, out var value)
            ? ReadStringValue(value)
            : null;
    }

    private static string? ReadStringValue(JsonElement value) =>
        value.ValueKind switch
        {
            JsonValueKind.String => value.GetString(),
            JsonValueKind.Number => value.GetRawText(),
            _ => null
        };

    private static int ReadInt(JsonElement element, string property) =>
        element.TryGetProperty(property, out var value)
        && value.TryGetInt32(out var result)
            ? result
            : 0;

    private static long ReadLong(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value))
        {
            return 0;
        }
        if (value.TryGetInt64(out var number))
        {
            return number;
        }
        return double.TryParse(value.GetRawText(),
            System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var parsed)
            ? (long)parsed
            : 0;
    }

    private static double ReadDouble(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var value))
        {
            return 0;
        }
        return value.TryGetDouble(out var number) ? number : 0;
    }

    private static bool IsHost(string host, string candidate) =>
        host.Equals(candidate, StringComparison.OrdinalIgnoreCase)
        || host.EndsWith("." + candidate, StringComparison.OrdinalIgnoreCase);

    private static string CleanYtDlpError(string message)
    {
        const string prefix = "ERROR: ";
        if (message.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            message = message[prefix.Length..];
        }
        return Abbreviate(message, 260);
    }

    private static string Abbreviate(string value, int maxLength) =>
        value.Length <= maxLength ? value : value[..(maxLength - 1)] + "…";

    private sealed record FetchedPage(Uri Url, string Html);

    private static class UserAgent
    {
        public const string Working =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";
    }
}
