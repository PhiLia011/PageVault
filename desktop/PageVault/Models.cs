using System.IO;

namespace PageVault;

public sealed class PageItem
{
    public required string Id { get; init; }
    public required string Title { get; init; }
    public required string Url { get; init; }
    public required string DirectoryPath { get; init; }
    public required long CapturedAt { get; init; }
    public required long Bytes { get; init; }
    public required int Images { get; init; }
    public required int Videos { get; init; }
    public required int Audios { get; init; }
    public required int Attachments { get; init; }
    public required string Summary { get; init; }

    public string ReaderPath => Path.Combine(DirectoryPath, "index.html");

    public string CapturedText =>
        DateTimeOffset.FromUnixTimeMilliseconds(CapturedAt).ToLocalTime()
            .ToString("MM月dd日 HH:mm");

    public string SizeText => PageStore.FormatBytes(Bytes);

    public string AssetText
    {
        get
        {
            var parts = new List<string> { $"{Images} 图", $"{Videos} 视频" };
            if (Audios > 0)
            {
                parts.Add($"{Audios} 音频");
            }
            if (Attachments > 0)
            {
                parts.Add($"{Attachments} 附件");
            }
            return string.Join(" · ", parts);
        }
    }

    public string ListMeta => $"{CapturedText} · {AssetText} · {SizeText}";
}

public sealed record ScrapeProgress(int Percent, string Message);

public sealed record ScrapeResult(
    string Id,
    string Title,
    int Images,
    int Videos,
    int Audios,
    int Attachments,
    long Bytes);
