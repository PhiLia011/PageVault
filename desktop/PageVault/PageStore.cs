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
}
