package com.pagevault.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

public final class DownloadLocation {
    private static final String PREFS = "download_location";
    private static final String TREE_URI = "tree_uri";
    public static final int REQUEST_PICK_TREE = 91;

    private DownloadLocation() {
    }

    public static Uri get(Context context) {
        String value = preferences(context).getString(TREE_URI, null);
        return value == null ? null : Uri.parse(value);
    }

    public static void save(Activity activity, Uri uri) {
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        preferences(activity).edit().putString(TREE_URI, uri.toString()).apply();
    }

    public static void pick(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_PICK_TREE);
    }

    public static String displayName(Context context) {
        Uri uri = get(context);
        if (uri == null) {
            return "尚未选择保存位置";
        }
        String value = Uri.decode(uri.getLastPathSegment());
        return value == null ? uri.toString() : value.replace("primary:", "内部存储/");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
