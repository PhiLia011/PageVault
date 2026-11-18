package com.pagevault.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int COLOR_PAPER = Color.rgb(246, 244, 238);
    private static final int COLOR_INK = Color.rgb(36, 49, 45);
    private static final int COLOR_MUTED = Color.rgb(109, 121, 116);
    private static final int COLOR_GREEN = Color.rgb(30, 91, 79);
    private static final int COLOR_ACCENT = Color.rgb(232, 116, 59);
    private static final int COLOR_LINE = Color.rgb(222, 219, 210);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<PageStore.PageItem> pages = new ArrayList<>();

    private EditText urlInput;
    private Button crawlButton;
    private LinearLayout statusCard;
    private TextView statusText;
    private ProgressBar progressBar;
    private TextView savedCount;
    private TextView saveLocationText;
    private PageAdapter adapter;
    private PageStore.PageItem currentPage;
    private Button textDownloadButton;
    private Button imageDownloadButton;
    private Button videoDownloadButton;
    private Button audioDownloadButton;
    private Button attachmentDownloadButton;
    private Runnable pendingDownloadAction;
    private File pagesRoot;
    private File previewRoot;
    private boolean working;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        pagesRoot = PageStore.pagesRoot(getFilesDir());
        PageStore.removeIncomplete(pagesRoot);
        previewRoot = new File(getCacheDir(), "previews");
        if (savedInstanceState == null) {
            Scraper.deleteRecursively(previewRoot);
        }
        if (!previewRoot.exists() && !previewRoot.mkdirs()) {
            Toast.makeText(this, "无法创建预览缓存", Toast.LENGTH_LONG).show();
        }
        setContentView(buildUi());
        refreshPages();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_PAPER);
        window.setNavigationBarColor(COLOR_PAPER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_PAPER);
        root.setPadding(dp(20), statusBarHeight() + dp(16), dp(20), dp(12));

        TextView brand = text("拾页", 38, COLOR_GREEN, Typeface.SERIF);
        brand.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        brand.setLetterSpacing(-0.02f);
        root.addView(brand, matchWrap());

        TextView subtitle = text("解析网页与视频，想保存时再下载", 14, COLOR_MUTED,
                Typeface.SANS_SERIF);
        subtitle.setPadding(0, dp(1), 0, dp(22));
        root.addView(subtitle, matchWrap());

        TextView inputLabel = text("网页地址", 12, COLOR_ACCENT, Typeface.SANS_SERIF);
        inputLabel.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        inputLabel.setLetterSpacing(0.12f);
        inputLabel.setPadding(dp(2), 0, 0, dp(8));
        root.addView(inputLabel, matchWrap());

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("https://example.com/article");
        urlInput.setHintTextColor(Color.rgb(151, 159, 155));
        urlInput.setTextColor(COLOR_INK);
        urlInput.setTextSize(15);
        urlInput.setPadding(dp(15), 0, dp(15), 0);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setBackground(rounded(Color.WHITE, dp(12), 1, COLOR_LINE));
        LinearLayout.LayoutParams inputParams =
                new LinearLayout.LayoutParams(0, dp(54), 1);
        inputRow.addView(urlInput, inputParams);

        crawlButton = actionButton("解析 / 抓取", COLOR_GREEN, Color.WHITE);
        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(dp(112), dp(54));
        buttonParams.setMargins(dp(10), 0, 0, 0);
        inputRow.addView(crawlButton, buttonParams);
        crawlButton.setOnClickListener(view -> startCrawl());
        root.addView(inputRow, matchWrap());

        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        statusCard.setBackground(rounded(Color.WHITE, dp(12), 1, COLOR_LINE));
        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(12), 0, 0);

        statusText = text("准备就绪", 14, COLOR_INK, Typeface.SANS_SERIF);
        statusCard.addView(statusText, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.setMargins(0, dp(10), 0, 0);
        statusCard.addView(progressBar, progressParams);

        LinearLayout resultActions = new LinearLayout(this);
        resultActions.setOrientation(LinearLayout.HORIZONTAL);
        resultActions.setPadding(0, dp(12), 0, 0);
        Button openButton = secondaryButton("打开阅读");
        Button exportButton = secondaryButton("下载全部 ZIP");
        LinearLayout.LayoutParams half =
                new LinearLayout.LayoutParams(0, dp(44), 1);
        resultActions.addView(openButton, half);
        LinearLayout.LayoutParams halfWithGap =
                new LinearLayout.LayoutParams(0, dp(44), 1);
        halfWithGap.setMargins(dp(8), 0, 0, 0);
        resultActions.addView(exportButton, halfWithGap);
        openButton.setOnClickListener(view -> openCurrentPage());
        exportButton.setOnClickListener(view -> exportPage(currentPage));
        statusCard.addView(resultActions, matchWrap());

        LinearLayout kindActionsOne = new LinearLayout(this);
        kindActionsOne.setOrientation(LinearLayout.HORIZONTAL);
        kindActionsOne.setPadding(0, dp(8), 0, 0);
        textDownloadButton = secondaryButton("网页 / 文字");
        imageDownloadButton = secondaryButton("图片 (0)");
        videoDownloadButton = secondaryButton("视频 (0)");
        addWeightedButton(kindActionsOne, textDownloadButton, 0);
        addWeightedButton(kindActionsOne, imageDownloadButton, 8);
        addWeightedButton(kindActionsOne, videoDownloadButton, 8);
        textDownloadButton.setOnClickListener(view -> downloadKind(currentPage, "text"));
        imageDownloadButton.setOnClickListener(view -> downloadKind(currentPage, "image"));
        videoDownloadButton.setOnClickListener(view -> downloadKind(currentPage, "video"));
        statusCard.addView(kindActionsOne, matchWrap());

        LinearLayout kindActionsTwo = new LinearLayout(this);
        kindActionsTwo.setOrientation(LinearLayout.HORIZONTAL);
        kindActionsTwo.setPadding(0, dp(8), 0, 0);
        audioDownloadButton = secondaryButton("音频 (0)");
        attachmentDownloadButton = secondaryButton("附件 (0)");
        Button locationButton = secondaryButton("保存位置");
        addWeightedButton(kindActionsTwo, audioDownloadButton, 0);
        addWeightedButton(kindActionsTwo, attachmentDownloadButton, 8);
        addWeightedButton(kindActionsTwo, locationButton, 8);
        audioDownloadButton.setOnClickListener(view -> downloadKind(currentPage, "audio"));
        attachmentDownloadButton.setOnClickListener(view ->
                downloadKind(currentPage, "attachment"));
        locationButton.setOnClickListener(view -> chooseSaveLocation());
        statusCard.addView(kindActionsTwo, matchWrap());

        saveLocationText = text("保存位置：" + DownloadLocation.displayName(this),
                11, COLOR_MUTED, Typeface.SANS_SERIF);
        saveLocationText.setPadding(0, dp(8), 0, 0);
        statusCard.addView(saveLocationText, matchWrap());
        setDownloadButtons(null);
        root.addView(statusCard, statusParams);
        statusCard.setVisibility(View.GONE);

        LinearLayout savedHeading = new LinearLayout(this);
        savedHeading.setOrientation(LinearLayout.HORIZONTAL);
        savedHeading.setGravity(Gravity.CENTER_VERTICAL);
        savedHeading.setPadding(0, dp(24), 0, dp(9));
        TextView savedTitle = text("历史归档", 20, COLOR_INK, Typeface.SANS_SERIF);
        savedTitle.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        savedHeading.addView(savedTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        savedCount = text("0 页", 13, COLOR_MUTED, Typeface.SANS_SERIF);
        savedHeading.addView(savedCount);
        root.addView(savedHeading, matchWrap());

        FrameLayout listHolder = new FrameLayout(this);
        LinearLayout.LayoutParams listParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        adapter = new PageAdapter();
        ListView listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, dp(10));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                openPage(pages.get(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(pages.get(position));
            return true;
        });
        listHolder.addView(listView, matchMatch());

        TextView empty = text("没有历史归档\n解析结果只做临时预览，手动下载不会自动加入这里。",
                15, COLOR_MUTED, Typeface.SANS_SERIF);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        listHolder.addView(empty, matchMatch());
        listView.setEmptyView(empty);
        root.addView(listHolder, listParams);
        return root;
    }

    private void startCrawl() {
        if (working) {
            return;
        }
        String url;
        try {
            url = Scraper.normalizeUrl(urlInput.getText().toString());
        } catch (IllegalArgumentException error) {
            urlInput.setError(error.getMessage());
            return;
        }
        if (currentPage != null
                && currentPage.directory.getAbsolutePath().startsWith(
                previewRoot.getAbsolutePath())) {
            Scraper.deleteRecursively(currentPage.directory);
            currentPage = null;
        }
        working = true;
        crawlButton.setEnabled(false);
        crawlButton.setAlpha(.55f);
        statusCard.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("准备抓取…");
        setDownloadButtons(null);
        executor.execute(() -> {
            try {
                Scraper.PageResult result = Scraper.scrape(MainActivity.this, url, previewRoot,
                        (percent, message) -> mainHandler.post(() -> {
                            progressBar.setProgress(percent);
                            statusText.setText(message);
                        }));
                mainHandler.post(() -> {
                    working = false;
                    crawlButton.setEnabled(true);
                    crawlButton.setAlpha(1f);
                    currentPage = PageStore.loadSingle(new File(previewRoot, result.id));
                    setDownloadButtons(currentPage);
                    statusText.setText("预览已生成「" + result.title + "」\n"
                            + formatMeta(result.images + " 张图片",
                            result.videos + " 个视频", formatBytes(result.bytes),
                            "尚未保存"));
                    progressBar.setProgress(100);
                    Toast.makeText(this, "预览已生成，尚未保存",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    working = false;
                    crawlButton.setEnabled(true);
                    crawlButton.setAlpha(1f);
                    statusCard.setVisibility(View.VISIBLE);
                    progressBar.setProgress(0);
                    statusText.setText("解析失败\n" + friendlyError(error));
                });
            }
        });
    }

    private void refreshPages() {
        pages.clear();
        pages.addAll(PageStore.load(pagesRoot));
        adapter.notifyDataSetChanged();
        savedCount.setText(pages.size() + " 页");
    }

    private void openCurrentPage() {
        if (currentPage != null) {
            openPage(currentPage);
        }
    }

    private void openPage(PageStore.PageItem page) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra("pagePath", page.directory.getAbsolutePath());
        intent.putExtra("title", page.title);
        startActivity(intent);
    }

    private void confirmDelete(PageStore.PageItem page) {
        new AlertDialog.Builder(this)
                .setTitle("删除这份离线页面？")
                .setMessage(page.title)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    Scraper.deleteRecursively(page.directory);
                    if (currentPage == page) {
                        currentPage = null;
                        statusCard.setVisibility(View.GONE);
                    }
                    refreshPages();
                })
                .show();
    }

    private void exportPage(PageStore.PageItem page) {
        if (page == null) {
            return;
        }
        withSaveLocation(() -> performZipDownload(page));
    }

    private void downloadKind(PageStore.PageItem page, String kind) {
        if (page == null) {
            return;
        }
        withSaveLocation(() -> performKindDownload(page, kind));
    }

    private void withSaveLocation(Runnable action) {
        if (DownloadLocation.get(this) == null) {
            pendingDownloadAction = action;
            Toast.makeText(this, "请选择保存位置", Toast.LENGTH_SHORT).show();
            DownloadLocation.pick(this);
            return;
        }
        action.run();
    }

    private void chooseSaveLocation() {
        pendingDownloadAction = null;
        DownloadLocation.pick(this);
    }

    private void performZipDownload(PageStore.PageItem page) {
        Toast.makeText(this, "正在打包 ZIP…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                String name = Exporter.exportZip(this, page.directory, page.title,
                        DownloadLocation.get(this));
                mainHandler.post(() -> Toast.makeText(this,
                        "ZIP 已下载到 " + name, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                mainHandler.post(() -> showError("下载失败", error));
            }
        });
    }

    private void performKindDownload(PageStore.PageItem page, String kind) {
        executor.execute(() -> {
            try {
                String path = Exporter.exportKinds(this, page.directory, page.title,
                        DownloadLocation.get(this),
                        java.util.Collections.singleton(kind));
                mainHandler.post(() -> Toast.makeText(this,
                        "已下载到 " + path, Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                mainHandler.post(() -> showError("下载失败", error));
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DownloadLocation.REQUEST_PICK_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                DownloadLocation.save(this, data.getData());
                if (saveLocationText != null) {
                    saveLocationText.setText("保存位置："
                            + DownloadLocation.displayName(this));
                }
                Runnable action = pendingDownloadAction;
                pendingDownloadAction = null;
                if (action != null) {
                    action.run();
                }
            } else {
                pendingDownloadAction = null;
                Toast.makeText(this, "未选择保存位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setDownloadButtons(PageStore.PageItem page) {
        boolean hasPage = page != null;
        textDownloadButton.setEnabled(hasPage);
        imageDownloadButton.setEnabled(hasPage && page.images > 0);
        videoDownloadButton.setEnabled(hasPage && page.videos > 0);
        audioDownloadButton.setEnabled(hasPage && page.audios > 0);
        attachmentDownloadButton.setEnabled(hasPage && page.attachments > 0);
        imageDownloadButton.setText("图片 (" + (hasPage ? page.images : 0) + ")");
        videoDownloadButton.setText("视频 (" + (hasPage ? page.videos : 0) + ")");
        audioDownloadButton.setText("音频 (" + (hasPage ? page.audios : 0) + ")");
        attachmentDownloadButton.setText("附件 (" + (hasPage ? page.attachments : 0) + ")");
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(friendlyError(error))
                .setPositiveButton("知道了", null)
                .show();
    }

    private String friendlyError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        if (message.toLowerCase(Locale.US).contains("cleartext")) {
            return "该网站不允许明文 HTTP 访问";
        }
        if (message.toLowerCase(Locale.US).contains("unable to resolve host")) {
            return "无法解析域名，请检查网络或网址";
        }
        return message;
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private final class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pages.size();
        }

        @Override
        public PageStore.PageItem getItem(int position) {
            return pages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return pages.get(position).capturedAt;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PageStore.PageItem item = getItem(position);
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(rounded(Color.WHITE, dp(12), 1, COLOR_LINE));
            LinearLayout.LayoutParams cardParams =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, dp(9));
            card.setLayoutParams(cardParams);

            TextView title = text(item.title, 17, COLOR_INK, Typeface.SANS_SERIF);
            title.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            title.setMaxLines(2);
            card.addView(title, matchWrap());

            String date = new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                    .format(new Date(item.capturedAt));
            TextView meta = text(formatMeta(date, item.images + " 张图片",
                    item.videos + " 个视频", formatBytes(item.bytes)),
                    12, COLOR_MUTED, Typeface.SANS_SERIF);
            meta.setPadding(0, dp(6), 0, 0);
            meta.setMaxLines(2);
            card.addView(meta, matchWrap());
            return card;
        }
    }

    private Button actionButton(String label, int fill, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        button.setBackground(ripple(fill, dp(12)));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = actionButton(label, Color.rgb(235, 240, 235), COLOR_GREEN);
        button.setTextSize(13);
        return button;
    }

    private void addWeightedButton(LinearLayout row, Button button, int leftMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMargins(dp(leftMargin), 0, 0, 0);
        row.addView(button, params);
    }

    private GradientDrawable rounded(int fill, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(dp(strokeWidth), strokeColor);
        }
        return drawable;
    }

    private RippleDrawable ripple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                rounded(fill, radius, 0, fill), null);
    }

    private TextView text(String value, int sizeSp, int color, Typeface family) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(family);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.CHINA, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    private static String formatMeta(String... values) {
        return String.join(" · ", values);
    }
}
