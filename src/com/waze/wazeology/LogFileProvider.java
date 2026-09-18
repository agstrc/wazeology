package com.waze.wazeology;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal, self-contained content provider that hands out the exported log file as a shareable
 * {@code content://} stream. Used instead of androidx {@code FileProvider} (not available here, and it
 * would need a compiled {@code @xml} paths resource, which this build forbids). Serves read-only files
 * from a single fixed cache subdirectory ({@code cacheDir/wazeology-share}); everything else is denied.
 */
public final class LogFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.waze.wazeology.logfiles";
    private static final String SHARE_DIR = "wazeology-share";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Resolve the URI's last path segment to a file in the share dir, rejecting any path traversal. */
    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.indexOf('/') >= 0 || name.contains("..") || getContext() == null) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File dir = new File(getContext().getCacheDir(), SHARE_DIR);
        File f = new File(dir, name);
        if (!f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        // Report a filename and size so the receiving app labels the attachment sensibly.
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {f.getName(), f.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
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
