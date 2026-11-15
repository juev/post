package org.evsyukov.post.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves the media fixtures of the test APK's assets as {@code content://org.evsyukov.post.test.share/<name>}. It runs
 * in the test APK's own process, which has no Kotlin runtime (the app APK carries it), so it is written in Java.
 */
public final class TestShareProvider extends ContentProvider {
    public static final String AUTHORITY = "org.evsyukov.post.test.share";

    public static Uri uri(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            return getContext().getAssets().openFd(uri.getLastPathSegment());
        } catch (IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try (AssetFileDescriptor fd = getContext().getAssets().openFd(uri.getLastPathSegment())) {
            cursor.addRow(new Object[] {uri.getLastPathSegment(), fd.getLength()});
        } catch (IOException e) {
            // An unknown name has no row.
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
