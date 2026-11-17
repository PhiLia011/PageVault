package com.pagevault.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Exporter {
    private Exporter() {
    }

    public static String exportBundle(Context context, File pageDirectory, String title)
            throws Exception {
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ").trim();
        if (safeTitle.isEmpty()) {
            safeTitle = "网页";
        }
        if (safeTitle.length() > 42) {
            safeTitle = safeTitle.substring(0, 42);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String displayName = "PageVault_" + safeTitle + "_" + stamp + ".zip";

        File cacheZip = new File(context.getCacheDir(), "export_" + stamp + ".zip");
        try {
            zipDirectory(pageDirectory, cacheZip);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToMediaStore(context, cacheZip, displayName);
            } else {
                File downloads = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), "PageVault");
                if (!downloads.exists() && !downloads.mkdirs()) {
                    throw new IllegalStateException("无法创建 Downloads/PageVault 目录");
                }
                copy(cacheZip, new File(downloads, displayName));
            }
            return displayName;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cacheZip.delete();
        }
    }

    private static void writeToMediaStore(Context context, File source, String displayName)
            throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/PageVault");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IllegalStateException("系统未创建导出文件");
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("无法写入导出文件");
            }
            copy(input, output);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
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

    private static void copy(File source, File target) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }
}
