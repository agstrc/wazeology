package com.wazeology.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns whatever the user picked into a classified split bundle: a base apk plus zero or more
 * config splits. Accepts loose .apk files or an XAPK/APKM/APKS container (a zip of apks, which is
 * what apk-pure hands out). The base is the apk that contains classes.dex; everything else is a split.
 */
public final class BundleInput {

    /** A classified input bundle. */
    public static final class Bundle {
        public final File base;
        public final List<File> splits;

        Bundle(File base, List<File> splits) {
            this.base = base;
            this.splits = splits;
        }
    }

    private BundleInput() {}

    /** Container extensions that are really zips of apks. */
    private static boolean looksLikeContainer(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".xapk") || n.endsWith(".apkm") || n.endsWith(".apks")
                || n.endsWith(".zip");
    }

    /**
     * Flatten the picked files into a list of apks: containers are unpacked (one level) into
     * workDir, loose .apk files pass through. Non-apk members of a container are ignored.
     */
    public static List<File> flatten(List<File> picked, File workDir) throws IOException {
        return flatten(picked, workDir, null, CancelToken.NONE);
    }

    /** {@link #flatten(List, File)} with byte progress over the unpacked data and cancellation. */
    public static List<File> flatten(List<File> picked, File workDir, Progress progress,
            CancelToken cancel) throws IOException {
        workDir.mkdirs();
        long total = 0;
        for (File f : picked) {
            if (looksLikeContainer(f.getName())) {
                try (ZipFile z = new ZipFile(f)) {
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                        ZipEntry ze = e.nextElement();
                        if (ze.getName().toLowerCase().endsWith(".apk") && ze.getSize() > 0) {
                            total += ze.getSize();
                        }
                    }
                }
            }
        }
        long done = 0;
        List<File> out = new ArrayList<File>();
        for (File f : picked) {
            if (!looksLikeContainer(f.getName())) {
                if (f.getName().toLowerCase().endsWith(".apk")) {
                    out.add(f);
                }
                continue;
            }
            try (ZipFile z = new ZipFile(f)) {
                int n = 0;
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                    ZipEntry ze = e.nextElement();
                    String name = ze.getName();
                    if (!name.toLowerCase().endsWith(".apk") || name.contains("/")) {
                        continue; // only top-level apks inside the container
                    }
                    File dst = new File(workDir, new File(name).getName());
                    try (InputStream in = z.getInputStream(ze);
                            OutputStream os = new FileOutputStream(dst)) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            cancel.check();
                            os.write(buf, 0, r);
                            done += r;
                            if (progress != null) {
                                progress.onBytes(done, total);
                            }
                        }
                    }
                    out.add(dst);
                    n++;
                }
                if (n == 0) {
                    throw new IOException("no apks inside " + f.getName());
                }
            }
        }
        return out;
    }

    public static boolean isBaseApk(File apk) throws IOException {
        return Graft.hasEntry(apk, "classes.dex");
    }

    /** Classify: exactly one apk must be a base (contains classes.dex); the rest are splits. */
    public static Bundle classify(List<File> apks) throws IOException {
        File base = null;
        List<File> splits = new ArrayList<File>();
        for (File f : apks) {
            if (isBaseApk(f)) {
                if (base != null) {
                    throw new IOException("more than one base apk in the bundle: "
                            + base.getName() + " and " + f.getName());
                }
                base = f;
            } else {
                splits.add(f);
            }
        }
        if (base == null) {
            throw new IOException("no base apk found (none of the picked apks contains classes.dex)");
        }
        return new Bundle(base, splits);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
    }

    /** Convenience for the app: read a small file fully. */
    public static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            copy(in, buf);
            return buf.toByteArray();
        }
    }
}
