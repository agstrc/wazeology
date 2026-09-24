package com.wazeology.installer.core;

import com.wazeology.core.Io;
import com.wazeology.core.Pins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The cached, verified Waze input: either the APKPure XAPK or the files the rider picked, kept under
 * root/sources/&lt;pinned versionCode&gt;/ so a rebuild, a reinstall or a retry after a failed install never
 * downloads again. root is meant to be the app's no-backup files dir, which the OS never evicts
 * (unlike cacheDir), so the ~180 MB survive storage pressure until the rider deletes them.
 *
 * A source counts only once source.properties exists and every listed file has its recorded size;
 * that file is written last, via a temp file and a rename, so a crash never leaves a half source that
 * looks complete. An in-progress download lives beside it as download.part + part.properties.
 */
public final class SourceCache {

    public static final String ORIGIN_APKPURE = "apkpure";
    public static final String ORIGIN_PICKED = "picked";

    /** A complete source: the files a build starts from. */
    public static final class Source {
        /** Content id (the XAPK SHA-1, or a SHA-256 over the picked files); part of the build key. */
        public final String id;
        public final String origin;
        public final List<File> files;
        public final long bytes;

        Source(String id, String origin, List<File> files, long bytes) {
            this.id = id;
            this.origin = origin;
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** What a resumable download needs to continue: the asset it is fetching and the server's ETag. */
    public static final class Partial {
        public final String sha1;
        public final long size;
        public final String etag;
        public final String type;
        public final long have;

        Partial(String sha1, long size, String etag, String type, long have) {
            this.sha1 = sha1;
            this.size = size;
            this.etag = etag;
            this.type = type;
            this.have = have;
        }
    }

    private final File sourcesRoot;
    private final File dir;
    private final File filesDir;

    public SourceCache(File root) {
        this.sourcesRoot = new File(root, "sources");
        this.dir = new File(sourcesRoot, String.valueOf(Pins.WAZE_VERSION_CODE));
        this.filesDir = new File(dir, "files");
    }

    // --- complete source ---

    public synchronized Source current() {
        Properties p = load(new File(dir, "source.properties"));
        if (p == null) {
            return null;
        }
        String id = p.getProperty("id");
        String names = p.getProperty("files", "");
        if (id == null || names.isEmpty()) {
            return null;
        }
        List<File> files = new ArrayList<File>();
        long bytes = 0;
        for (String name : names.split("/")) {
            File f = new File(filesDir, name);
            long want = parseLong(p.getProperty("size." + name), -1);
            if (!f.isFile() || f.length() != want) {
                return null;
            }
            files.add(f);
            bytes += want;
        }
        return new Source(id, p.getProperty("origin", ORIGIN_APKPURE), files, bytes);
    }

    /**
     * Make files the current source, moving them into the cache (they must sit on the same
     * filesystem, e.g. the work dir under the same root). The previous source is dropped first so a
     * crash mid-way leaves no source rather than a mixed one.
     */
    public synchronized Source promote(List<File> files, String id, String origin) throws IOException {
        File props = new File(dir, "source.properties");
        props.delete();
        Io.deleteTree(filesDir);
        if (!filesDir.mkdirs() && !filesDir.isDirectory()) {
            throw new IOException("cannot create " + filesDir);
        }
        Properties p = new Properties();
        StringBuilder names = new StringBuilder();
        for (File f : files) {
            File dst = new File(filesDir, f.getName());
            if (!f.renameTo(dst)) {
                throw new IOException("cannot move " + f.getName() + " into the cache");
            }
            if (names.length() > 0) {
                names.append('/');
            }
            names.append(dst.getName());
            p.setProperty("size." + dst.getName(), String.valueOf(dst.length()));
        }
        p.setProperty("id", id);
        p.setProperty("origin", origin);
        p.setProperty("files", names.toString());
        p.setProperty("versionCode", String.valueOf(Pins.WAZE_VERSION_CODE));
        store(p, props);
        Source s = current();
        if (s == null) {
            throw new IOException("the cached source did not verify after the move");
        }
        return s;
    }

    // --- partial download ---

    public File partFile() {
        return new File(dir, "download.part");
    }

    public synchronized Partial partial() {
        Properties p = load(new File(dir, "part.properties"));
        File part = partFile();
        if (p == null || !part.isFile()) {
            return null;
        }
        return new Partial(p.getProperty("sha1"), parseLong(p.getProperty("size"), -1),
                p.getProperty("etag"), p.getProperty("type"), part.length());
    }

    /** Record which asset download.part belongs to (before the first byte is written). */
    public synchronized void beginPartial(String sha1, long size, String type) throws IOException {
        dir.mkdirs();
        Properties p = new Properties();
        if (sha1 != null) {
            p.setProperty("sha1", sha1);
        }
        p.setProperty("size", String.valueOf(size));
        if (type != null) {
            p.setProperty("type", type);
        }
        store(p, new File(dir, "part.properties"));
    }

    public synchronized void updateEtag(String etag) throws IOException {
        Properties p = load(new File(dir, "part.properties"));
        if (p == null || etag == null || etag.equals(p.getProperty("etag"))) {
            return;
        }
        p.setProperty("etag", etag);
        store(p, new File(dir, "part.properties"));
    }

    public synchronized void discardPartial() {
        partFile().delete();
        new File(dir, "part.properties").delete();
    }

    /** Move a verified download.part into place as the source. */
    public synchronized Source promoteDownload(String fileName, String sha1) throws IOException {
        File staged = new File(dir, fileName);
        staged.delete();
        if (!partFile().renameTo(staged)) {
            throw new IOException("cannot finalize the download");
        }
        new File(dir, "part.properties").delete();
        List<File> one = new ArrayList<File>();
        one.add(staged);
        return promote(one, sha1, ORIGIN_APKPURE);
    }

    // --- housekeeping ---

    /** Drop sources cached for any other Waze version (the pin moved on). */
    public synchronized void evictOthers() {
        File[] all = sourcesRoot.listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            if (!f.getName().equals(dir.getName())) {
                Io.deleteTree(f);
            }
        }
    }

    public synchronized void clear() {
        Io.deleteTree(sourcesRoot);
    }

    public synchronized long bytesOnDisk() {
        return Io.treeSize(sourcesRoot);
    }

    // --- helpers shared with BuildCache ---

    static Properties load(File f) {
        if (!f.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            return null;
        }
    }

    /** Write p to f atomically: a temp file, fsync, then rename over f. */
    static void store(Properties p, File f) throws IOException {
        f.getParentFile().mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, null);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) {
                throw new IOException("cannot write " + f.getName());
            }
        }
    }

    static long parseLong(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
