package com.wazeology.installer.core;

import com.wazeology.core.CancelToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Fetches one exact Waze build from APKPure, the same source scripts/fetch-apk.sh uses through
 * apkeep. The versions endpoint answers with a protobuf blob; like apkeep, this reads it without the
 * schema. Each downloadable asset record carries its SHA-1 as a 40-hex string (field 3), its type
 * ("XAPK" or "APK", field 8) and its download URL (field 9). The URL path segment after /b/XAPK/ is
 * base64url of "<package>_<versionCode>_<id>", which pins a candidate by versionCode rather than by
 * the version name. One build can have several assets (e.g. an arm64 + xxxhdpi bundle and an
 * armeabi-v7a + mdpi one); the server orders them by the x-abis preference sent with the request.
 */
public final class ApkPure {

    private static final String VERSIONS_URL =
            "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    /** One downloadable asset of the requested build. */
    public static final class Candidate {
        /** "XAPK" (a zip of base + config splits) or "APK". */
        public final String type;
        public final String url;
        public final int versionCode;
        /** Lowercase hex SHA-1 of the file as APKPure advertises it, or null when absent. */
        public final String sha1;
        /** Advertised size in bytes (the varint right after the SHA-1), or -1 when absent. */
        public final long size;

        Candidate(String type, String url, int versionCode, String sha1, long size) {
            this.type = type;
            this.url = url;
            this.versionCode = versionCode;
            this.sha1 = sha1;
            this.size = size;
        }

        /** A file name BundleInput classifies correctly (.xapk is unpacked, .apk passes through). */
        public String fileName(String pkg) {
            return pkg + "_" + versionCode + ("XAPK".equals(type) ? ".xapk" : ".apk");
        }
    }

    private ApkPure() {}

    /** Ask APKPure for every asset of pkg at exactly versionCode, best ABI match first. */
    public static List<Candidate> find(String pkg, int versionCode, String[] abis) throws IOException {
        HttpURLConnection c = open(VERSIONS_URL + pkg);
        c.setRequestProperty("x-cv", "3172501");
        c.setRequestProperty("x-sv", "29");
        c.setRequestProperty("x-abis", join(abis));
        c.setRequestProperty("x-gp", "1");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new Downloader.HttpStatus(code);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    body.write(buf, 0, r);
                }
            }
            return parse(body.toByteArray(), pkg, versionCode);
        } finally {
            c.disconnect();
        }
    }

    /** Pull the candidates for pkg at versionCode out of a versions response, in response order. */
    public static List<Candidate> parse(byte[] body, String pkg, int versionCode) {
        List<Candidate> out = new ArrayList<Candidate>();
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < body.length; i++) {
            String type;
            int p;
            if (matches(body, i, "B\u0004XAPKJ")) {
                type = "XAPK";
                p = i + 7;
            } else if (matches(body, i, "B\u0003APKJ")) {
                type = "APK";
                p = i + 6;
            } else {
                continue;
            }
            long len = 0;
            int shift = 0;
            while (p < body.length && shift < 35) {
                int b = body[p++] & 0xff;
                len |= (long) (b & 0x7f) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            if (len <= 0 || p + len > body.length) {
                continue;
            }
            String url = new String(body, p, (int) len, StandardCharsets.ISO_8859_1);
            int vc = urlVersionCode(url, type, pkg);
            if (vc != versionCode || seen.contains(url)) {
                continue;
            }
            seen.add(url);
            int shaAt = sha1At(body, i);
            out.add(new Candidate(type, url, vc, shaAt < 0 ? null : sha1Hex(body, shaAt),
                    shaAt < 0 ? -1 : sizeAfterSha1(body, shaAt)));
        }
        return out;
    }

    /**
     * Whether an XAPK carries the native config split for abi (e.g. "arm64-v8a" needs
     * config.arm64_v8a.apk). Without it the patched Waze has no native libs for the device.
     */
    public static boolean hasNativeSplit(File xapk, String abi) throws IOException {
        try (ZipFile z = new ZipFile(xapk)) {
            return z.getEntry(nativeSplitName(abi)) != null;
        }
    }

    /** The native config split an XAPK carries for abi, e.g. config.arm64_v8a.apk. */
    public static String nativeSplitName(String abi) {
        return "config." + abi.replace('-', '_') + ".apk";
    }

    /**
     * Whether an XAPK on the server carries the native split for abi, read from the zip's central
     * directory at the end of the file so a bundle for the wrong processor is skipped before its
     * ~180 MB download. Null when it cannot be told (no range support, or the listing did not fit
     * the tail), in which case the caller downloads and checks the file itself.
     */
    public static Boolean remoteHasNativeSplit(Candidate cand, String abi, CancelToken cancel) {
        try {
            byte[] tail = Downloader.tail(cand.url, 64 * 1024, cancel);
            if (tail == null) {
                return null;
            }
            int eocd = lastIndexOf(tail, "PK\u0005\u0006".getBytes(StandardCharsets.ISO_8859_1));
            if (eocd < 0 || eocd + 16 > tail.length) {
                return null;
            }
            long cdSize = (tail[eocd + 12] & 0xffL) | (tail[eocd + 13] & 0xffL) << 8
                    | (tail[eocd + 14] & 0xffL) << 16 | (tail[eocd + 15] & 0xffL) << 24;
            if (cdSize > eocd) {
                return null; // the listing starts before the tail: it cannot be told either way
            }
            // Search the listing only: the compressed data before it may hold the name by chance.
            byte[] name = nativeSplitName(abi).getBytes(StandardCharsets.ISO_8859_1);
            return indexOf(tail, name, eocd - (int) cdSize, eocd) >= 0 ? Boolean.TRUE : Boolean.FALSE;
        } catch (CancelToken.Cancelled e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int lastIndexOf(byte[] hay, byte[] needle) {
        for (int i = hay.length - needle.length; i >= 0; i--) {
            int j = 0;
            while (j < needle.length && hay[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    /** First index of needle lying wholly within hay[from, to), or -1. */
    private static int indexOf(byte[] hay, byte[] needle, int from, int to) {
        outer:
        for (int i = from; i + needle.length <= to; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // --- response parsing ---

    private static boolean matches(byte[] body, int at, String marker) {
        if (at + marker.length() > body.length) {
            return false;
        }
        for (int k = 0; k < marker.length(); k++) {
            if ((body[at + k] & 0xff) != marker.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    /** versionCode from ".../b/<type>/<base64url pkg_vc_id>?...", or -1 when it is not pkg's. */
    private static int urlVersionCode(String url, String type, String pkg) {
        String marker = "/b/" + type + "/";
        int s = url.indexOf(marker);
        if (!url.startsWith("https://") || s < 0) {
            return -1;
        }
        s += marker.length();
        int e = url.indexOf('?', s);
        String seg = e < 0 ? url.substring(s) : url.substring(s, e);
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(pad(seg)), StandardCharsets.ISO_8859_1);
        } catch (IllegalArgumentException ex) {
            return -1;
        }
        String prefix = pkg + "_";
        if (!decoded.startsWith(prefix)) {
            return -1;
        }
        int end = decoded.indexOf('_', prefix.length());
        try {
            return Integer.parseInt(end < 0 ? decoded.substring(prefix.length())
                    : decoded.substring(prefix.length(), end));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Offset of the asset's SHA-1 field (tag 0x1a, length 40, hex) shortly before its type field,
     *  or -1. */
    private static int sha1At(byte[] body, int typeAt) {
        for (int k = typeAt - 42; k >= Math.max(0, typeAt - 80); k--) {
            if (body[k] != 0x1a || body[k + 1] != 40) {
                continue;
            }
            for (int j = k + 2; j < k + 42; j++) {
                if (Character.digit((char) (body[j] & 0xff), 16) < 0) {
                    return -1;
                }
            }
            return k;
        }
        return -1;
    }

    private static String sha1Hex(byte[] body, int at) {
        StringBuilder sb = new StringBuilder(40);
        for (int j = at + 2; j < at + 42; j++) {
            sb.append(Character.toLowerCase((char) (body[j] & 0xff)));
        }
        return sb.toString();
    }

    /** The size field (tag 0x20, varint) that follows the SHA-1, or -1. */
    private static long sizeAfterSha1(byte[] body, int shaAt) {
        int p = shaAt + 42;
        if (p >= body.length || body[p] != 0x20) {
            return -1;
        }
        p++;
        long v = 0;
        for (int shift = 0; p < body.length && shift < 63; shift += 7) {
            int b = body[p++] & 0xff;
            v |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
        }
        return -1;
    }

    private static String pad(String b64) {
        StringBuilder sb = new StringBuilder(b64);
        while (sb.length() % 4 != 0) {
            sb.append('=');
        }
        return sb.toString();
    }

    // --- http + io ---

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
