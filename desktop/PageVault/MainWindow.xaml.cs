using Microsoft.Web.WebView2.Core;
using Microsoft.Win32;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace PageVault;

public partial class MainWindow : Window
{
    private readonly PageStore _store = new();
    private readonly ScraperService _scraper = new();
    private readonly ObservableCollection<PageItem> _pages = [];
    private CancellationTokenSource? _crawlCancellation;
    private bool _webViewReady;
    private bool _busy;
    private PageItem? _selectedPage;
    private string? _pendingPageId;

    public MainWindow()
    {
        InitializeComponent();
        LibraryList.ItemsSource = _pages;
        UrlInput.Focus();
        Loaded += MainWindow_Loaded;
        Closing += MainWindow_Closing;
        LibraryList.ContextMenu = BuildLibraryContextMenu();
    }

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshLibrary();
        await EnsureWebViewAsync();
    }

    private async Task EnsureWebViewAsync()
    {
        if (_webViewReady)
        {
            return;
        }
        try
        {
            await Reader.EnsureCoreWebView2Async();
            Reader.CoreWebView2.Settings.IsScriptEnabled = false;
            Reader.CoreWebView2.Settings.AreHostObjectsAllowed = false;
            Reader.CoreWebView2.Settings.IsWebMessageEnabled = false;
            Reader.CoreWebView2.Settings.AreDevToolsEnabled = false;
            Reader.CoreWebView2.Settings.AreDefaultContextMenusEnabled = true;
            Reader.CoreWebView2.Settings.IsStatusBarEnabled = false;
            Reader.CoreWebView2.NavigationStarting += CoreWebView2_NavigationStarting;
            Reader.CoreWebView2.NewWindowRequested += CoreWebView2_NewWindowRequested;
            _webViewReady = true;
        }
        catch (Exception error)
        {
            ShowStatus("阅读组件启动失败：" + error.Message);
            MessageBox.Show(
                "未能启动 Microsoft Edge WebView2 运行时。\n\n"
                + "请安装 Microsoft Edge WebView2 Runtime 后重新打开拾页。\n\n"
                + error.Message,
                "阅读组件不可用", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void RefreshLibrary(string? selectId = null)
    {
        var currentId = selectId ?? _selectedPage?.Id;
        _pages.Clear();
        foreach (var page in _store.Load())
        {
            _pages.Add(page);
        }
        ItemsCountText.Text = $"{_pages.Count} 页 · {PageStore.FormatBytes(_pages.Sum(p => p.Bytes))}";
        EmptyLibraryText.Visibility = _pages.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        var selected = currentId is null
            ? _pages.FirstOrDefault()
            : _pages.FirstOrDefault(page => page.Id == currentId) ?? _pages.FirstOrDefault();
        LibraryList.SelectedItem = selected;
        if (selected is null)
        {
            ClearSelection();
        }
    }

    private async void LibraryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (LibraryList.SelectedItem is not PageItem page)
        {
            ClearSelection();
            return;
        }
        _selectedPage = page;
        CurrentPageTitle.Text = page.Title;
        CurrentPageUrl.Text = page.Url;
        CurrentPageUrl.ToolTip = page.Url;
        SourceButton.IsEnabled = true;
        ExportButton.IsEnabled = true;
        FolderButton.IsEnabled = true;
        DeleteButton.IsEnabled = true;
        await ShowPageAsync(page);
    }

    private async Task ShowPageAsync(PageItem page)
    {
        if (!File.Exists(page.ReaderPath))
        {
            MessageBox.Show("本地阅读页不存在，可能已被移动或删除。",
                "无法打开", MessageBoxButton.OK, MessageBoxImage.Warning);
            RefreshLibrary();
            return;
        }
        await EnsureWebViewAsync();
        if (!_webViewReady)
        {
            return;
        }
        Reader.Visibility = Visibility.Visible;
        EmptyReader.Visibility = Visibility.Collapsed;
        Reader.CoreWebView2.Navigate(new Uri(page.ReaderPath).AbsoluteUri);
        BottomStatusText.Text =
            $"{page.AssetText} · {page.SizeText} · 保存于 {page.CapturedText}";
    }

    private void ClearSelection()
    {
        _selectedPage = null;
        CurrentPageTitle.Text = "等待选择";
        CurrentPageUrl.Text = "抓取后可在应用内直接阅读";
        CurrentPageUrl.ToolTip = null;
        SourceButton.IsEnabled = false;
        ExportButton.IsEnabled = false;
        FolderButton.IsEnabled = false;
        DeleteButton.IsEnabled = false;
        EmptyReader.Visibility = Visibility.Visible;
        Reader.Visibility = Visibility.Collapsed;
        if (_webViewReady)
        {
            Reader.CoreWebView2.Navigate("about:blank");
        }
    }

    private async void CrawlButton_Click(object sender, RoutedEventArgs e)
    {
        if (_busy)
        {
            return;
        }
        string url;
        try
        {
            url = ScraperService.NormalizeUrl(UrlInput.Text);
            UrlInput.Text = url;
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "网址有误",
                MessageBoxButton.OK, MessageBoxImage.Information);
            UrlInput.Focus();
            return;
        }

        _busy = true;
        SetBusyState(true);
        _crawlCancellation = new CancellationTokenSource();
        var progress = new Progress<ScrapeProgress>(update =>
        {
            CrawlProgress.Value = update.Percent;
            CrawlStatusText.Text = update.Message;
            BottomStatusText.Text = update.Message;
        });

        try
        {
            var result = await _scraper.ScrapeAsync(
                url, _store.Root, progress, _crawlCancellation.Token);
            _pendingPageId = result.Id;
            RefreshLibrary(result.Id);
            ShowStatus($"已保存「{result.Title}」 · {result.Images} 图 · "
                       + $"{result.Videos} 视频 · {PageStore.FormatBytes(result.Bytes)}");
            CrawlStatusText.Text = "已保存";
        }
        catch (OperationCanceledException)
        {
            ShowStatus("已取消，未保留不完整文件");
            CrawlStatusText.Text = "已取消";
        }
        catch (Exception error)
        {
            var message = FriendlyError(error);
            ShowStatus("保存失败：" + message);
            CrawlStatusText.Text = "失败";
            MessageBox.Show(message, "未能保存网页",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        finally
        {
            _crawlCancellation?.Dispose();
            _crawlCancellation = null;
            _pendingPageId = null;
            _busy = false;
            SetBusyState(false);
        }
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        _crawlCancellation?.Cancel();
        CrawlStatusText.Text = "正在取消…";
    }

    private void SetBusyState(bool busy)
    {
        CrawlButton.IsEnabled = !busy;
        CancelButton.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        CrawlProgressPanel.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        CrawlProgress.Value = busy ? 0 : 100;
        if (busy)
        {
            CrawlStatusText.Text = "准备抓取…";
        }
    }

    private void PasteButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (Clipboard.ContainsText())
            {
                UrlInput.Text = Clipboard.GetText().Trim();
                UrlInput.CaretIndex = UrlInput.Text.Length;
            }
        }
        catch
        {
            ShowStatus("无法读取剪贴板");
        }
        UrlInput.Focus();
    }

    private void UrlInput_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            CrawlButton_Click(sender, e);
            e.Handled = true;
        }
    }

    private void SourceButton_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedPage is not null)
        {
            OpenExternal(_selectedPage.Url);
        }
    }

    private void ExportButton_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedPage is null)
        {
            return;
        }
        var safeName = Regex.Replace(_selectedPage.Title, @"[\\/:*?""<>|\p{Cc}]", "_")
            .Trim();
        if (safeName.Length > 42)
        {
            safeName = safeName[..42];
        }
        if (string.IsNullOrWhiteSpace(safeName))
        {
            safeName = "网页";
        }
        var dialog = new SaveFileDialog
        {
            Title = "导出完整离线包",
            Filter = "ZIP 压缩包 (*.zip)|*.zip",
            DefaultExt = ".zip",
            FileName = $"PageVault_{safeName}_{DateTime.Now:yyyyMMdd_HHmmss}.zip",
            InitialDirectory = Environment.GetFolderPath(
                Environment.SpecialFolder.MyDocuments)
        };
        if (dialog.ShowDialog(this) != true)
        {
            return;
        }
        try
        {
            _store.Export(_selectedPage, dialog.FileName);
            ShowStatus("已导出：" + dialog.FileName);
            MessageBox.Show("完整离线包已导出。\n\n" + dialog.FileName,
                "导出完成", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "导出失败",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OpenPageFolder_Click(object sender, RoutedEventArgs e)
    {
        if (_selectedPage is not null)
        {
            try
            {
                PageStore.OpenFolder(_selectedPage.DirectoryPath);
            }
            catch (Exception error)
            {
                MessageBox.Show(error.Message, "无法打开目录",
                    MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }
    }

    private void OpenLibraryFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            PageStore.OpenFolder(_store.Root);
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "无法打开目录",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void DeleteButton_Click(object sender, RoutedEventArgs e) => DeleteSelectedPage();

    private ContextMenu BuildLibraryContextMenu()
    {
        var deleteItem = new MenuItem { Header = "删除这份离线页面" };
        deleteItem.Click += (_, _) => DeleteSelectedPage();
        var menu = new ContextMenu();
        menu.Items.Add(deleteItem);
        menu.Opened += (_, _) =>
        {
            if (LibraryList.SelectedItem is PageItem page)
            {
                _selectedPage = page;
            }
        };
        return menu;
    }

    private void DeleteSelectedPage()
    {
        if (_selectedPage is null)
        {
            return;
        }
        var result = MessageBox.Show(
            $"确定删除「{_selectedPage.Title}」及其中所有离线资源？\n\n此操作不可撤销。",
            "删除离线页面", MessageBoxButton.YesNo, MessageBoxImage.Warning,
            MessageBoxResult.No);
        if (result != MessageBoxResult.Yes)
        {
            return;
        }
        try
        {
            _store.Delete(_selectedPage);
            _selectedPage = null;
            RefreshLibrary();
            ShowStatus("已删除本地离线页面");
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "删除失败",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void CoreWebView2_NewWindowRequested(
        object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled = true;
        OpenExternal(e.Uri);
    }

    private void CoreWebView2_NavigationStarting(
        object? sender, CoreWebView2NavigationStartingEventArgs e)
    {
        if (e.Uri == "about:blank" || IsReaderResource(e.Uri))
        {
            return;
        }
        e.Cancel = true;
        OpenExternal(e.Uri);
    }

    private bool IsReaderResource(string value)
    {
        if (_selectedPage is null || !Uri.TryCreate(value, UriKind.Absolute, out var uri)
                                  || uri.Scheme != Uri.UriSchemeFile)
        {
            return false;
        }
        var pageRoot = Path.GetFullPath(_selectedPage.DirectoryPath)
                       + Path.DirectorySeparatorChar;
        var target = Path.GetFullPath(uri.LocalPath);
        return target.StartsWith(pageRoot, StringComparison.OrdinalIgnoreCase)
               && File.Exists(target);
    }

    private static void OpenExternal(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)
            || (uri.Scheme != Uri.UriSchemeHttp
                && uri.Scheme != Uri.UriSchemeHttps
                && uri.Scheme != Uri.UriSchemeMailto))
        {
            return;
        }
        try
        {
            Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch
        {
            // Missing protocol handlers should not interrupt reading.
        }
    }

    private static string FriendlyError(Exception error)
    {
        if (error is TaskCanceledException or TimeoutException)
        {
            return "请求超时，请稍后重试或检查网络";
        }
        if (error is HttpRequestException requestError)
        {
            return "无法读取网页：" + requestError.Message;
        }
        return error.Message;
    }

    private void ShowStatus(string message)
    {
        BottomStatusText.Text = message;
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        _crawlCancellation?.Cancel();
        Reader.Dispose();
    }
}
