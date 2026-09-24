package com.wazeology.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Zip-level APK surgery: rewrites a copy of the pristine base.apk, entry by entry, swapping in the
 * patched hook dexes, the rebuilt binary AndroidManifest.xml, and (optionally) the bytes of one
 * existing res/* entry (the launcher icon), then appending the payload dex. Everything else is
 * copied with its compression method preserved, and resources.arsc must come out byte-identical
 * (the golden rule: nothing may re-encode the resource table).
 *
 * Pure java.util.zip so the exact code runs on the phone and in the host-JVM gate.
 */
public final class Graft {

    /** Alignment of STORED entries in the output zip. DEX and .so entries are stored uncompressed
     *  and must be page-aligned for ART / the dynamic linker to map them. */
    public static final int STORED_ALIGNMENT = 4096;

    /** Bumped by hand whenever graft or signing logic changes the output bytes, so builds cached on
     *  the phone by an older installer are redone instead of reused (see BuildCache.key). */
    public static final int FORMAT = 1;

    public interface Progress {
        void onEntry(String name);

        void onWarning(String message);
    }

    private static final Progress SILENT = new Progress() {
        @Override
        public void onEntry(String name) {
        }

        @Override
        public void onWarning(String message) {
        }
    };

    /** The set of entry changes one graft applies. */
    public static final class PatchSet {
        final Map<String, byte[]> replace = new LinkedHashMap<String, byte[]>();
        final Map<String, byte[]> replaceIfPresent = new LinkedHashMap<String, byte[]>();
        final Map<String, byte[]> append = new LinkedHashMap<String, byte[]>();

        /** Swap the entry (fatal if missing in the pristine apk). */
        public PatchSet replaceEntry(String name, byte[] bytes) {
            replace.put(name, bytes);
            return this;
        }

        /** Swap the entry only when it exists (the icon overwrite: tolerate a missing target). */
        public PatchSet replaceIfPresent(String name, byte[] bytes) {
            replaceIfPresent.put(name, bytes);
            return this;
        }

        /** Add a new entry (fatal if the name already exists). */
        public PatchSet appendEntry(String name, byte[] bytes) {
            append.put(name, bytes);
            return this;
        }
    }

    private Graft() {}

    public static void graft(File pristine, File out, PatchSet patches, Progress progress)
            throws IOException {
        if (progress == null) {
            progress = SILENT;
        }

        // Pass 1: collect entry names in order, verify the patch set fits, and hash resources.arsc.
        List<String> names = new ArrayList<String>();
        byte[] pristineArscHash = null;
        try (ZipFile zf = new ZipFile(pristine)) {
            Set<String> seen = new java.util.TreeSet<String>();
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                names.add(n);
                seen.add(n);
            }
            for (String must : patches.replace.keySet()) {
                if (!seen.contains(must)) {
                    throw new IOException("patch target missing in the base apk: " + must);
                }
            }
            for (String add : patches.append.keySet()) {
                if (seen.contains(add)) {
                    throw new IOException("append collides with an existing entry: " + add);
                }
            }
            ZipEntry arsc = zf.getEntry(Pins.ARSC_ENTRY);
            if (arsc == null) {
                throw new IOException("no " + Pins.ARSC_ENTRY + " in the base apk");
            }
            pristineArscHash = sha256(readFully(zf, arsc));
        }

        // Pass 2: rewrite. Untouched entries keep their compression method (deflated entries are
        // re-deflated at BEST_SPEED so a ~90 MB rewrite stays quick on a phone). META-INF v1
        // signature remnants from the mirror's re-signing are dropped; the fresh v2/v3 signature
        // replaces them.
        long total = 0;
        try (ZipFile zf = new ZipFile(pristine);
                FileOutputStream fos = new FileOutputStream(out);
                AlignedZip zos = new AlignedZip(fos)) {
            for (String n : names) {
                if (n.startsWith("META-INF/")) {
                    continue;
                }
                progress.onEntry(n);
                ZipEntry ze = zf.getEntry(n);
                byte[] swap = patches.replace.get(n);
                if (swap == null) {
                    swap = patches.replaceIfPresent.get(n);
                    if (swap != null) {
                        total += writeEntry(zos, n, swap, ze.getTime(), ze.getMethod(), ze.getSize());
                        continue;
                    }
                } else {
                    // Hook dexes ship STORED like the original build wrote them.
                    int method = n.endsWith(".dex") ? ZipEntry.STORED : ze.getMethod();
                    total += writeEntry(zos, n, swap, ze.getTime(), method, ze.getSize());
                    continue;
                }
                total += copyEntry(zf, zos, ze);
            }
            for (Map.Entry<String, byte[]> e : patches.append.entrySet()) {
                progress.onEntry(e.getKey());
                total += writeEntry(zos, e.getKey(), e.getValue(), System.currentTimeMillis(),
                        ZipEntry.STORED, -1);
            }
            for (String missing : patches.replaceIfPresent.keySet()) {
                if (!names.contains(missing)) {
                    progress.onWarning("icon target " + missing
                            + " not present in this base; skipped (cosmetic only)");
                }
            }
        }
        if (pristineArscHash == null) {
            throw new IOException("no resources.arsc hash");
        }

        // The golden rule, enforced on-device: the graft must never change the resource table.
        byte[] outArscHash;
        try (ZipFile zf = new ZipFile(out)) {
            ZipEntry arsc = zf.getEntry(Pins.ARSC_ENTRY);
            if (arsc == null) {
                throw new IOException("grafted apk lost " + Pins.ARSC_ENTRY);
            }
            outArscHash = sha256(readFully(zf, arsc));
        }
        if (!MessageDigest.isEqual(pristineArscHash, outArscHash)) {
            out.delete();
            throw new IOException("resources.arsc changed by the graft - refusing to ship it "
                    + "(the golden rule: the resource table must stay byte-identical)");
        }
    }

    private static long copyEntry(ZipFile zf, ZipOutputStream zos, ZipEntry ze) throws IOException {
        ZipEntry outEntry = new ZipEntry(ze.getName());
        outEntry.setTime(ze.getTime());
        outEntry.setMethod(ze.getMethod());
        if (ze.getSize() >= 0) {
            outEntry.setSize(ze.getSize());
            if (ze.getCrc() >= 0) {
                outEntry.setCrc(ze.getCrc());
            }
        }
        zos.putNextEntry(outEntry);
        InputStream in = zf.getInputStream(ze);
        byte[] buf = new byte[1 << 16];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            zos.write(buf, 0, r);
            n += r;
        }
        in.close();
        zos.closeEntry();
        return n;
    }

    private static long writeEntry(ZipOutputStream zos, String name, byte[] bytes, long time,
            int method, long unusedSize) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(time);
        e.setMethod(method);
        if (method == ZipEntry.STORED) {
            e.setSize(bytes.length);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            e.setCrc(crc.getValue());
        }
        zos.putNextEntry(e);
        zos.write(bytes);
        zos.closeEntry();
        return bytes.length;
    }

    // --- entry inspection helpers shared with the host gate ---

    /** Dex names of an apk in numeric order (classes.dex, classes2.dex, ... classesN.dex). */
    public static List<String> dexNames(File apk) throws IOException {
        List<String> names = new ArrayList<String>();
        try (ZipFile zf = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zf.entries(); e.hasMoreElements();) {
                String n = e.nextElement().getName();
                if (n.equals("classes.dex") || n.matches("classes[0-9]+\\.dex")) {
                    names.add(n);
                }
            }
        }
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.valueOf(dexIndex(a)).compareTo(Integer.valueOf(dexIndex(b)));
            }
        });
        return names;
    }

    private static int dexIndex(String name) {
        if (name.equals("classes.dex")) {
            return 1;
        }
        return Integer.parseInt(name.substring("classes".length(), name.length() - ".dex".length()));
    }

    public static boolean hasEntry(File apk, String name) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            return zf.getEntry(name) != null;
        }
    }

    public static byte[] readEntry(File apk, String name) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) {
                throw new IOException("no entry " + name + " in " + apk);
            }
            return readFully(zf, e);
        }
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] readFully(ZipFile zf, ZipEntry e) throws IOException {
        byte[] buf = new byte[(int) e.getSize()];
        InputStream in = zf.getInputStream(e);
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                throw new IOException("short read on " + e.getName());
            }
            off += r;
        }
        in.close();
        return buf;
    }

    /**
     * ZipOutputStream that page-aligns STORED entries by padding the local-header extra field, and
     * deflates at BEST_SPEED so the rewrite is fast on device hardware.
     */
    static final class AlignedZip extends ZipOutputStream {
        private final CountingOutputStream counter;

        AlignedZip(OutputStream out) {
            this(new CountingOutputStream(out));
        }

        private AlignedZip(CountingOutputStream counter) {
            super(counter);
            this.counter = counter;
            def.setLevel(Deflater.BEST_SPEED);
        }

        @Override
        public void putNextEntry(ZipEntry e) throws IOException {
            if (e.getMethod() == STORED && !e.isDirectory()) {
                int nameLen = e.getName().getBytes(StandardCharsets.UTF_8).length;
                long header = 30L + nameLen + (e.getExtra() == null ? 0 : e.getExtra().length);
                int pad = (int) ((STORED_ALIGNMENT - ((counter.count + header) % STORED_ALIGNMENT))
                        % STORED_ALIGNMENT);
                if (pad > 0) {
                    byte[] old = e.getExtra();
                    byte[] neu = new byte[(old == null ? 0 : old.length) + pad];
                    if (old != null) {
                        System.arraycopy(old, 0, neu, 0, old.length);
                    }
                    e.setExtra(neu);
                }
            }
            super.putNextEntry(e);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
