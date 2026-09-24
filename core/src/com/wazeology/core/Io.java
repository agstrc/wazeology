package com.wazeology.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** File and digest helpers shared by the build engine and the installer's caches. */
public final class Io {

    private Io() {}

    public static String sha1Hex(File f, CancelToken cancel, Progress progress) throws IOException {
        return digestHex("SHA-1", f, cancel, progress);
    }

    public static String digestHex(String algorithm, File f, CancelToken cancel, Progress progress)
            throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(algorithm + " unavailable", e);
        }
        long total = f.length();
        long done = 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                cancel.check();
                md.update(buf, 0, r);
                done += r;
                if (progress != null) {
                    progress.onBytes(done, total);
                }
            }
        }
        return hex(md.digest());
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }

    public static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    public static long treeSize(File f) {
        if (f.isFile()) {
            return f.length();
        }
        long n = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                n += treeSize(k);
            }
        }
        return n;
    }
}
