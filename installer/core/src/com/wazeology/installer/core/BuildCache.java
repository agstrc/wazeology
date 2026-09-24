package com.wazeology.installer.core;

import com.wazeology.core.Graft;
import com.wazeology.core.Io;
import com.wazeology.core.Pins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The finished, signed Waze with Wazeology under root/builds/&lt;key&gt;/, so Install (and a reinstall
 * after a failure) is instant. The key covers everything the output depends on: the source content,
 * the patch assets + signing key baked into this installer, the pins and {@link Graft#FORMAT}. An
 * installer update with new assets therefore rebuilds from the cached source without downloading.
 *
 * A build is written into &lt;key&gt;.tmp and renamed into place only after every apk is signed and the
 * COMPLETE list is written, so a crash or cancel never leaves a partial set that could be installed.
 */
public final class BuildCache {

    private static final String COMPLETE = "COMPLETE";

    private final File dir;

    public BuildCache(File root) {
        this.dir = new File(root, "builds");
    }

    public static String key(String sourceId, String assetsDigest) {
        String material = "format=" + Graft.FORMAT + "\nversionCode=" + Pins.WAZE_VERSION_CODE
                + "\nsource=" + sourceId + "\nassets=" + assetsDigest + "\n";
        return Io.hex(Graft.sha256(material.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
    }

    /** A fresh, empty temp directory for building key. */
    public synchronized File begin(String key) throws IOException {
        File tmp = new File(dir, key + ".tmp");
        Io.deleteTree(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create " + tmp);
        }
        return tmp;
    }

    /** Seal the temp build: record the apks (base first) and their sizes, then rename into place. */
    public synchronized void commit(String key, List<File> ordered) throws IOException {
        File tmp = new File(dir, key + ".tmp");
        Properties p = new Properties();
        StringBuilder names = new StringBuilder();
        for (File f : ordered) {
            if (!f.getParentFile().equals(tmp)) {
                throw new IOException(f.getName() + " is not inside the build directory");
            }
            if (names.length() > 0) {
                names.append('/');
            }
            names.append(f.getName());
            p.setProperty("size." + f.getName(), String.valueOf(f.length()));
        }
        p.setProperty("files", names.toString());
        SourceCache.store(p, new File(tmp, COMPLETE));
        File done = new File(dir, key);
        Io.deleteTree(done);
        if (!tmp.renameTo(done)) {
            throw new IOException("cannot finalize the build");
        }
    }

    /** The apks of a complete build (base first), or null when key has no complete build. */
    public synchronized List<File> complete(String key) {
        if (key == null) {
            return null;
        }
        File d = new File(dir, key);
        Properties p = SourceCache.load(new File(d, COMPLETE));
        if (p == null) {
            return null;
        }
        String names = p.getProperty("files", "");
        if (names.isEmpty()) {
            return null;
        }
        List<File> out = new ArrayList<File>();
        for (String name : names.split("/")) {
            File f = new File(d, name);
            if (!f.isFile() || f.length() != SourceCache.parseLong(p.getProperty("size." + name), -1)) {
                return null;
            }
            out.add(f);
        }
        return out;
    }

    /** Remove every build except key, plus any temp directory left by a crash or cancel. */
    public synchronized void evictExcept(String key) {
        File[] all = dir.listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            if (key == null || !f.getName().equals(key)) {
                Io.deleteTree(f);
            }
        }
    }

    /** Remove temp directories only (a startup sweep while the key is not known yet). */
    public synchronized void cleanTemp() {
        File[] all = dir.listFiles();
        if (all == null) {
            return;
        }
        for (File f : all) {
            if (f.getName().endsWith(".tmp")) {
                Io.deleteTree(f);
            }
        }
    }

    public synchronized void clear() {
        Io.deleteTree(dir);
    }

    public synchronized long bytesOnDisk() {
        return Io.treeSize(dir);
    }
}
