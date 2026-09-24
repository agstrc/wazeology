package com.wazeology.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal binary AXML reader: pulls package, versionCode and versionName out of a compiled
 * AndroidManifest.xml. Hand-rolled on purpose (a few hundred lines of chunk walking beats a
 * dependency), and pure java.util/java.io so it runs identically on the host JVM and on Android.
 *
 * Format: a RES_XML_TYPE header chunk, a string pool chunk, an optional resource map chunk, then
 * element chunks. We only need the first start-element (the &lt;manifest&gt; root) and its attributes.
 */
public final class MiniAxml {

    private static final int CHUNK_STRING_POOL = 0x0001;
    private static final int CHUNK_XML = 0x0003;
    private static final int CHUNK_START_ELEMENT = 0x0102;

    private static final int STRING_POOL_UTF8_FLAG = 0x00000100;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;

    /** What the build needs to know about a candidate base.apk. */
    public static final class ManifestInfo {
        public final String pkg;
        public final int versionCode;
        public final String versionName;

        ManifestInfo(String pkg, int versionCode, String versionName) {
            this.pkg = pkg;
            this.versionCode = versionCode;
            this.versionName = versionName;
        }

        @Override
        public String toString() {
            return pkg + " versionCode=" + versionCode + " versionName=" + versionName;
        }
    }

    private MiniAxml() {}

    public static ManifestInfo read(byte[] data) throws IOException {
        CountingInputStream counter = new CountingInputStream(new ByteArrayInputStream(data));
        DataInputStream in = new DataInputStream(counter);

        int type = readU16(in);
        int headerSize = readU16(in);
        long fileSize = readU32(in);
        if (type != CHUNK_XML) {
            throw new IOException("not a binary XML (chunk type 0x" + Integer.toHexString(type) + ")");
        }
        skipFully(in, headerSize - 8); // chunk header fields already consumed

        String[] pool = null;
        long consumed = headerSize;
        while (consumed < fileSize) {
            long chunkStart = counter.count;
            int ctype = readU16(in);
            int cheaderSize = readU16(in);
            long csize = readU32(in);
            if (csize < cheaderSize || csize < 8) {
                throw new IOException("bad chunk size " + csize);
            }
            if (ctype == CHUNK_STRING_POOL) {
                pool = readStringPool(in, counter, chunkStart, csize);
            } else if (ctype == CHUNK_START_ELEMENT) {
                return readStartElement(in, counter, pool, chunkStart, csize);
            } else {
                skipFully(in, csize - 8);
            }
            consumed += csize;
        }
        throw new IOException("no start element in binary XML");
    }

    /** Consumes one string pool chunk, leaving the stream at the end of the chunk. */
    private static String[] readStringPool(DataInputStream in, CountingInputStream counter,
            long chunkStart, long chunkSize) throws IOException {
        long stringCount = readU32(in);
        long styleCount = readU32(in);
        long flags = readU32(in);
        long stringsStart = readU32(in);
        readU32(in); // stylesStart

        for (int i = 0; i < stringCount + styleCount; i++) {
            readU32(in); // string/style offsets (unused; the data starts at stringsStart)
        }
        skipFully(in, chunkStart + stringsStart - counter.count);

        boolean utf8 = (flags & STRING_POOL_UTF8_FLAG) != 0;
        String[] strings = new String[(int) stringCount];
        for (int i = 0; i < stringCount; i++) {
            strings[i] = readPoolString(in, utf8);
        }
        skipFully(in, chunkStart + chunkSize - counter.count); // style data + padding
        return strings;
    }

    private static String readPoolString(DataInputStream in, boolean utf8) throws IOException {
        if (utf8) {
            int len = in.readUnsignedByte();
            if ((len & 0x80) != 0) {
                len = ((len & 0x7f) << 8) | in.readUnsignedByte();
            }
            byte[] buf = new byte[len];
            in.readFully(buf);
            in.readUnsignedByte(); // trailing NUL
            return new String(buf, StandardCharsets.UTF_8);
        }
        int len = readU16(in);
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | readU16(in);
        }
        byte[] buf = new byte[len * 2];
        in.readFully(buf);
        in.readUnsignedByte(); // trailing NUL
        in.readUnsignedByte();
        return new String(buf, StandardCharsets.UTF_16LE);
    }

    /** Reads the first start element (the manifest root) and returns its identity attributes. */
    private static ManifestInfo readStartElement(DataInputStream in, CountingInputStream counter,
            String[] pool, long chunkStart, long chunkSize) throws IOException {
        if (pool == null) {
            throw new IOException("start element before string pool");
        }
        readU32(in); // line number
        readU32(in); // comment
        readU32(in); // namespace
        readU32(in); // element name
        int attrStart = readU16(in);
        int attrSize = readU16(in);
        int attrCount = readU16(in);
        readU16(in); // id index
        readU16(in); // class index
        readU16(in); // style index
        if (attrCount <= 0 || attrSize < 20) {
            throw new IOException("start element without attributes");
        }
        // AttributeStart is relative to the chunk start; in a well-formed element the attributes
        // follow the fixed header directly, but seek explicitly so a padded chunk still parses.
        skipFully(in, chunkStart + attrStart - counter.count);

        String pkg = null;
        int versionCode = -1;
        String versionName = null;
        for (int i = 0; i < attrCount; i++) {
            readU32(in); // namespace index
            int nameIdx = (int) readU32(in);
            int rawValueIdx = (int) readU32(in);
            readU16(in); // typed value size
            in.readUnsignedByte(); // res0
            int dataType = in.readUnsignedByte();
            long data = readU32(in);

            String name = (nameIdx >= 0 && nameIdx < pool.length) ? pool[nameIdx] : null;
            String raw = (rawValueIdx >= 0 && rawValueIdx < pool.length) ? pool[rawValueIdx] : null;
            if (name == null) {
                continue;
            }
            if (name.equals("package") || name.endsWith(":package")) {
                pkg = stringValue(dataType, data, pool, raw);
            } else if (name.equals("versionCode") || name.endsWith(":versionCode")) {
                versionCode = intValue(dataType, data, raw);
            } else if (name.equals("versionName") || name.endsWith(":versionName")) {
                versionName = stringValue(dataType, data, pool, raw);
            }
        }
        return new ManifestInfo(pkg, versionCode, versionName);
    }

    private static String stringValue(int dataType, long data, String[] pool, String raw) {
        if (dataType == TYPE_STRING && data >= 0 && data < pool.length) {
            return pool[(int) data];
        }
        return raw;
    }

    private static int intValue(int dataType, long data, String raw) {
        if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) {
            return (int) data;
        }
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    // --- little-endian readers ---

    private static int readU16(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static long readU32(DataInputStream in) throws IOException {
        return readU16(in) | ((long) readU16(in) << 16);
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("unexpected end of binary XML");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            count += s;
            return s;
        }
    }
}
