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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity {
    private static final int COLOR_PAPER = Color.rgb(246, 244, 238);
    private static final int COLOR_INK = Color.rgb(36, 49, 45);
    private static final int COLOR_GREEN = Color.rgb(30, 91, 79);
    private static final int COLOR_ACCENT = Color.rgb(232, 116, 59);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private File pageDirectory;
    private String pageTitle;
    private Button exportButton;
    private boolean exportPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        String path = getIntent().getStringExtra("pagePath");
        pageTitle = getIntent().getStringExtra("title");
        if (TextUtils.isEmpty(pageTitle)) {
            pageTitle = "阅读";
        }
        pageDirectory = path == null ? null : new File(path);
        File index = pageDirectory == null ? null : new File(pageDirectory, "index.html");
        if (index == null || !index.isFile()) {
            Toast.makeText(this, "找不到本地阅读页", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(buildUi());
        webView.loadUrl(index.toURI().toString());
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

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), statusBarHeight() + dp(8), dp(12), dp(8));

        Button back = compactButton("‹");
        back.setTextSize(29);
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));

        TextView title = new TextView(this);
        title.setText(pageTitle);
        title.setTextColor(COLOR_INK);
        title.setTextSize(16);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(0, dp(46), 1);
        titleParams.setMargins(dp(4), 0, dp(8), 0);
        toolbar.addView(title, titleParams);

        exportButton = compactButton("ZIP");
        exportButton.setTextSize(13);
        exportButton.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        exportButton.setOnClickListener(view -> exportPage());
        toolbar.addView(exportButton, new LinearLayout.LayoutParams(dp(72), dp(42)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(222, 219, 210));
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        webView.setBackgroundColor(Color.rgb(250, 248, 242));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
        if ("file".equals(scheme)) {
            String pageRoot = pageDirectory.getAbsoluteFile().toURI().toString();
            String target = uri.toString();
            if (target.startsWith(pageRoot)) {
                return false;
            }
        }
        if ("http".equals(scheme) || "https".equals(scheme)
                || "mailto".equals(scheme) || "tel".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    private void exportPage() {
        if (exportPending || pageDirectory == null) {
            return;
        }
        if (DownloadLocation.get(this) == null) {
            Toast.makeText(this, "请选择保存位置", Toast.LENGTH_SHORT).show();
            DownloadLocation.pick(this);
            return;
        }
        performExport();
    }

    private void performExport() {
        if (exportPending) {
            return;
        }
        exportPending = true;
        exportButton.setEnabled(false);
        exportButton.setAlpha(.55f);
        Toast.makeText(this, "正在打包 ZIP…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                String name = Exporter.exportZip(this, pageDirectory, pageTitle,
                        DownloadLocation.get(this));
                mainHandler.post(() -> {
                    exportPending = false;
                    exportButton.setEnabled(true);
                    exportButton.setAlpha(1f);
                    showExportSuccess(name);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    exportPending = false;
                    exportButton.setEnabled(true);
                    exportButton.setAlpha(1f);
                    new AlertDialog.Builder(this)
                            .setTitle("导出失败")
                            .setMessage(error.getMessage() == null
                                    ? error.getClass().getSimpleName() : error.getMessage())
                            .setPositiveButton("知道了", null)
                            .show();
                });
            }
        });
    }

    private void showExportSuccess(String name) {
        new AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setMessage(name + "\n\n已保存到你选择的位置。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == DownloadLocation.REQUEST_PICK_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                DownloadLocation.save(this, data.getData());
                performExport();
            } else {
                Toast.makeText(this, "未选择保存位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }

    private Button compactButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(COLOR_GREEN);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(235, 240, 235));
        background.setCornerRadius(dp(10));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(COLOR_ACCENT), background, null));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }
}
