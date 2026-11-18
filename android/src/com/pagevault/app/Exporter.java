package com.pagevault.app;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Exporter {
    private Exporter() {
    }

    public static String exportKinds(
            Context context,
            File pageDirectory,
            String title,
            Uri treeUri,
            Set<String> kinds) throws Exception {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            throw new IllegalStateException("保存位置不可写，请重新选择");
        }
        String folderName = safeName(title) + "_" + timestamp();
        DocumentFile folder = tree.createDirectory(folderName);
        if (folder == null) {
            throw new IllegalStateException("无法在所选位置创建下载目录");
        }

        copyIfExists(context, new File(pageDirectory, "manifest.json"),
                folder, "manifest.json", "application/json");
        if (kinds.contains("text")) {
            copyIfExists(context, new File(pageDirectory, "index.html"),
                    folder, "index.html", "text/html");
            copyIfExists(context, new File(pageDirectory, "article.txt"),
                    folder, "article.txt", "text/plain");
        }

        File manifestFile = new File(pageDirectory, "manifest.json");
        JSONObject manifest = new JSONObject(readUtf8(manifestFile));
        JSONArray files = manifest.optJSONArray("files");
        if (files == null) {
            throw new IllegalStateException("预览中没有可下载的文件清单");
        }
        DocumentFile assets = folder.findFile("assets");
        if (assets == null) {
            assets = folder.createDirectory("assets");
        }
        if (assets == null) {
            throw new IllegalStateException("无法创建 assets 目录");
        }
        String root = pageDirectory.getCanonicalPath() + File.separator;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null || !kinds.contains(item.optString("kind"))) {
                continue;
            }
            String relative = item.optString("file");
            File source = new File(pageDirectory, relative);
            if (!source.getCanonicalPath().startsWith(root) || !source.isFile()) {
                continue;
            }
            String name = source.getName();
            DocumentFile destination = assets.createFile(mimeFor(source), name);
            if (destination == null) {
                throw new IllegalStateException("无法创建文件：" + name);
            }
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = context.getContentResolver()
                         .openOutputStream(destination.getUri())) {
                if (output == null) {
                    throw new IllegalStateException("无法写入：" + name);
                }
                copy(input, output);
            }
        }
        return tree.getName() + "/" + folderName;
    }

    public static String exportZip(
            Context context,
            File pageDirectory,
            String title,
            Uri treeUri) throws Exception {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canWrite()) {
            throw new IllegalStateException("保存位置不可写，请重新选择");
        }
        String displayName = "PageVault_" + safeName(title) + "_" + timestamp() + ".zip";
        File cacheZip = new File(context.getCacheDir(), "export_" + timestamp() + ".zip");
        try {
            zipDirectory(pageDirectory, cacheZip);
            DocumentFile target = tree.findFile(displayName);
            if (target == null) {
                target = tree.createFile("application/zip", displayName);
            }
            if (target == null) {
                throw new IllegalStateException("无法创建 ZIP 文件");
            }
            try (InputStream input = new FileInputStream(cacheZip);
                 OutputStream output = context.getContentResolver()
                         .openOutputStream(target.getUri(), "w")) {
                if (output == null) {
                    throw new IllegalStateException("无法写入 ZIP 文件");
                }
                copy(input, output);
            }
            return tree.getName() + "/" + target.getName();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cacheZip.delete();
        }
    }

    private static void copyIfExists(
            Context context,
            File source,
            DocumentFile parent,
            String name,
            String mime) throws Exception {
        if (!source.isFile()) {
            return;
        }
        DocumentFile target = parent.findFile(name);
        if (target == null) {
            target = parent.createFile(mime, name);
        }
        if (target == null) {
            throw new IllegalStateException("无法创建：" + name);
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver()
                     .openOutputStream(target.getUri(), "wt")) {
            if (output == null) {
                throw new IllegalStateException("无法写入：" + name);
            }
            copy(input, output);
        }
    }

    private static String readUtf8(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            copy(input, output);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void zipDirectory(File directory, File target) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(
                new FileOutputStream(target), StandardCharsets.UTF_8)) {
            addDirectoryContents(zip, directory, "");
        }
    }

    private static void addDirectoryContents(ZipOutputStream zip, File directory, String prefix)
            throws Exception {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String entryName = prefix + child.getName();
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(entryName + "/"));
                zip.closeEntry();
                addDirectoryContents(zip, child, entryName + "/");
            } else {
                zip.putNextEntry(new ZipEntry(entryName));
                try (InputStream input = new FileInputStream(child)) {
                    copy(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private static String mimeFor(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static String safeName(String title) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) {
            safe = "网页";
        }
        return safe.length() > 42 ? safe.substring(0, 42) : safe;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
}
