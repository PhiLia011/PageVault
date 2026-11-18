using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text.Json;

namespace PageVault;

public sealed class PageStore
{
    private readonly JsonSerializerOptions _jsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public PageStore()
    {
        Root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PageVault",
            "pages");
        Directory.CreateDirectory(Root);
        RemoveIncomplete();
    }

    public string Root { get; }

    public IReadOnlyList<PageItem> Load()
    {
        var pages = new List<PageItem>();
        foreach (var directory in Directory.EnumerateDirectories(Root))
        {
            if (Path.GetFileName(directory).StartsWith('.'))
            {
                continue;
            }
            var manifestPath = Path.Combine(directory, "manifest.json");
            if (!File.Exists(manifestPath))
            {
                continue;
            }
            try
            {
                using var document = JsonDocument.Parse(File.ReadAllText(manifestPath));
                var root = document.RootElement;
                var assets = root.TryGetProperty("assets", out var assetElement)
                    ? assetElement
                    : default;
                pages.Add(new PageItem
                {
                    Id = GetString(root, "id", Path.GetFileName(directory)),
                    Title = GetString(root, "title", "未命名网页"),
                    Url = GetString(root, "url", ""),
                    DirectoryPath = directory,
                    CapturedAt = GetLong(root, "capturedAt",
                        new DateTimeOffset(File.GetLastWriteTimeUtc(manifestPath))
                            .ToUnixTimeMilliseconds()),
                    Bytes = GetLong(root, "bytes", DirectorySize(directory)),
                    Images = GetInt(assets, "images"),
                    Videos = GetInt(assets, "videos"),
                    Audios = GetInt(assets, "audios"),
                    Attachments = GetInt(assets, "attachments"),
                    Summary = GetString(root, "summary", "")
                });
            }
            catch
            {
                // A damaged entry is ignored; the original files remain untouched.
            }
        }
        return pages.OrderByDescending(page => page.CapturedAt).ToList();
    }

    public void Delete(PageItem page)
    {
        var fullRoot = Path.GetFullPath(Root) + Path.DirectorySeparatorChar;
        var fullTarget = Path.GetFullPath(page.DirectoryPath);
        if (!fullTarget.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("拒绝删除资料库以外的目录");
        }
        if (Directory.Exists(fullTarget))
        {
            Directory.Delete(fullTarget, true);
        }
    }

    public void Export(PageItem page, string destination)
    {
        if (File.Exists(destination))
        {
            File.Delete(destination);
        }
        ZipFile.CreateFromDirectory(page.DirectoryPath, destination,
            CompressionLevel.Optimal, false);
    }

    public string DownloadKinds(
        PageItem page,
        string destinationRoot,
        IReadOnlySet<string> kinds)
    {
        Directory.CreateDirectory(destinationRoot);
        var safeTitle = SanitizeName(page.Title);
        var target = Path.Combine(
            destinationRoot,
            $"{safeTitle}_{page.Id}");
        Directory.CreateDirectory(target);
        var assetsTarget = Path.Combine(target, "assets");
        Directory.CreateDirectory(assetsTarget);

        foreach (var metadata in new[] { "manifest.json" })
        {
            var source = Path.Combine(page.DirectoryPath, metadata);
            if (File.Exists(source))
            {
                File.Copy(source, Path.Combine(target, metadata), true);
            }
        }
        if (kinds.Contains("text"))
        {
            foreach (var document in new[] { "index.html", "article.txt" })
            {
                var source = Path.Combine(page.DirectoryPath, document);
                if (File.Exists(source))
                {
                    File.Copy(source, Path.Combine(target, document), true);
                }
            }
        }

        var manifestPath = Path.Combine(page.DirectoryPath, "manifest.json");
        if (!File.Exists(manifestPath))
        {
            throw new InvalidOperationException("预览清单不存在");
        }
        using var manifest = JsonDocument.Parse(File.ReadAllText(manifestPath));
        if (!manifest.RootElement.TryGetProperty("files", out var files)
            || files.ValueKind != JsonValueKind.Array)
        {
            throw new InvalidOperationException("预览中没有可下载的文件清单");
        }
        foreach (var file in files.EnumerateArray())
        {
            var kind = GetString(file, "kind", "");
            var relative = GetString(file, "file", "");
            if (!kinds.Contains(kind) || string.IsNullOrWhiteSpace(relative))
            {
                continue;
            }
            var source = Path.GetFullPath(Path.Combine(page.DirectoryPath, relative));
            var root = Path.GetFullPath(page.DirectoryPath) + Path.DirectorySeparatorChar;
            if (!source.StartsWith(root, StringComparison.OrdinalIgnoreCase)
                || !File.Exists(source))
            {
                continue;
            }
            var destination = Path.Combine(target, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.Copy(source, destination, true);
        }
        return target;
    }

    public string ExportToFolder(PageItem page, string destinationRoot)
    {
        Directory.CreateDirectory(destinationRoot);
        var safeTitle = SanitizeName(page.Title);
        var destination = Path.Combine(
            destinationRoot,
            $"PageVault_{safeTitle}_{DateTime.Now:yyyyMMdd_HHmmss}.zip");
        Export(page, destination);
        return destination;
    }

    public static void OpenFolder(string path)
    {
        var target = Directory.Exists(path) ? path : Path.GetDirectoryName(path) ?? path;
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{target}\"")
        {
            UseShellExecute = true
        });
    }

    private void RemoveIncomplete()
    {
        foreach (var directory in Directory.EnumerateDirectories(Root, ".tmp_*"))
        {
            try
            {
                Directory.Delete(directory, true);
            }
            catch
            {
                // A later launch will retry cleanup.
            }
        }
    }

    internal static long DirectorySize(string directory)
    {
        if (!Directory.Exists(directory))
        {
            return 0;
        }
        return Directory.EnumerateFiles(directory, "*", SearchOption.AllDirectories)
            .Sum(file =>
            {
                try
                {
                    return new FileInfo(file).Length;
                }
                catch
                {
                    return 0L;
                }
            });
    }

    public static string FormatBytes(long bytes)
    {
        return bytes switch
        {
            < 1024 => $"{bytes} B",
            < 1024 * 1024 => $"{bytes / 1024d:F1} KB",
            < 1024L * 1024 * 1024 => $"{bytes / 1024d / 1024d:F1} MB",
            _ => $"{bytes / 1024d / 1024d / 1024d:F2} GB"
        };
    }

    private static string GetString(JsonElement root, string name, string fallback) =>
        root.ValueKind == JsonValueKind.Object
        && root.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? fallback
            : fallback;

    private static long GetLong(JsonElement root, string name, long fallback) =>
        root.ValueKind == JsonValueKind.Object
        && root.TryGetProperty(name, out var value)
        && value.TryGetInt64(out var number)
            ? number
            : fallback;

    private static int GetInt(JsonElement root, string name) =>
        root.ValueKind == JsonValueKind.Object
        && root.TryGetProperty(name, out var value)
        && value.TryGetInt32(out var number)
            ? number
            : 0;

    private static string SanitizeName(string value)
    {
        var result = System.Text.RegularExpressions.Regex.Replace(
            value, @"[\\/:*?""<>|\p{Cc}]", "_").Trim();
        return string.IsNullOrWhiteSpace(result) ? "网页" : result[..Math.Min(48, result.Length)];
    }
}
