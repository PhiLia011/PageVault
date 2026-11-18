package com.pagevault.app;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class PageStore {
    public static final class PageItem {
        public final File directory;
        public final String title;
        public final String url;
        public final long capturedAt;
        public final long bytes;
        public final int images;
        public final int videos;
        public final int audios;
        public final int attachments;

        PageItem(File directory, JSONObject manifest) {
            this.directory = directory;
            this.title = manifest.optString("title", directory.getName());
            this.url = manifest.optString("url", "");
            this.capturedAt = manifest.optLong("capturedAt", directory.lastModified());
            this.bytes = manifest.optLong("bytes", Scraper.directorySize(directory));
            JSONObject assets = manifest.optJSONObject("assets");
            this.images = assets == null ? 0 : assets.optInt("images", 0);
            this.videos = assets == null ? 0 : assets.optInt("videos", 0);
            this.audios = assets == null ? 0 : assets.optInt("audios", 0);
            this.attachments = assets == null ? 0 : assets.optInt("attachments", 0);
        }
    }

    private PageStore() {
    }

    public static File pagesRoot(File filesDir) {
        File root = new File(filesDir, "pages");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("无法创建本地页面目录");
        }
        return root;
    }

    public static void removeIncomplete(File pagesRoot) {
        File[] children = pagesRoot.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(".tmp_")) {
                Scraper.deleteRecursively(child);
            }
        }
    }

    public static List<PageItem> load(File pagesRoot) {
        List<PageItem> pages = new ArrayList<>();
        File[] children = pagesRoot.listFiles();
        if (children == null) {
            return pages;
        }
        for (File child : children) {
            if (!child.isDirectory() || child.getName().startsWith(".")) {
                continue;
            }
            File manifest = new File(child, "manifest.json");
            if (!manifest.isFile()) {
                continue;
            }
            try {
                String json = new String(Files.readAllBytes(manifest.toPath()),
                        StandardCharsets.UTF_8);
                pages.add(new PageItem(child, new JSONObject(json)));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(pages, Comparator.comparingLong((PageItem item) -> item.capturedAt)
                .reversed());
        return pages;
    }

    public static PageItem loadSingle(File directory) {
        try {
            File manifest = new File(directory, "manifest.json");
            if (!manifest.isFile()) {
                return null;
            }
            String json = new String(Files.readAllBytes(manifest.toPath()),
                    StandardCharsets.UTF_8);
            return new PageItem(directory, new JSONObject(json));
        } catch (Exception ignored) {
            return null;
        }
    }
}
