package com.wazeology.installer.core;

import com.wazeology.core.CancelToken;
import com.wazeology.core.Progress;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Resumable HTTP download into a ".part" file. APKPure's download link 302s to a CDN (data.winudf.com)
 * that honours Range and sends an ETag, so an interrupted download continues from the bytes already on
 * disk: "Range: bytes=N-" plus "If-Range: etag" (a changed file answers 200 and restarts from zero).
 * Redirects are followed by hand so the Range headers survive every hop, and the transfer asks for
 * identity encoding (a transparently gunzipped body breaks both Content-Length and Range).
 */
public final class Downloader {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_REDIRECTS = 6;

    /** Receives the server's (strong) ETag as soon as the response headers arrive, so even a
     *  download cut on its first attempt resumes with If-Range. */
    public interface EtagSink {
        void onEtag(String etag) throws IOException;
    }

    /** A non-2xx answer; 5xx is worth a retry, 4xx only after a fresh link lookup. */
    public static final class HttpStatus extends IOException {
        public final int code;

        public HttpStatus(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private Downloader() {}

    /**
     * Continue downloading url into part from its current length until the server has sent the
     * whole file. Returns the ETag to store for the next resume (the one passed in when the server
     * sends none). The part file is kept on any failure, so the next call resumes.
     */
    public static String fetch(String url, File part, String etag, Progress progress, CancelToken cancel)
            throws IOException {
        return fetch(url, part, etag, null, progress, cancel);
    }

    public static String fetch(String url, File part, String etag, EtagSink sink, Progress progress,
            CancelToken cancel) throws IOException {
        if (etag != null && etag.startsWith("W/")) {
            etag = null; // a weak ETag never validates a range: the server would restart from zero
        }
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection c = null;
        try {
            c = connect(url, have > 0 ? "bytes=" + have + "-" : null, have > 0 ? etag : null, cancel);
            int code = c.getResponseCode();
            boolean append;
            long total;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                long[] range = contentRange(c.getHeaderField("Content-Range"));
                if (range == null || range[0] != have) {
                    // The server resumed somewhere else: start over rather than splice.
                    part.delete();
                    throw new IOException("server resumed at an unexpected offset");
                }
                append = true;
                total = range[2];
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                have = 0;
                total = c.getContentLengthLong();
            } else if (code == 416) {
                return etag; // nothing left to send: the caller verifies the file
            } else {
                throw new HttpStatus(code);
            }
            String newEtag = c.getHeaderField("ETag");
            if (newEtag != null && newEtag.startsWith("W/")) {
                newEtag = null;
            }
            if (sink != null && newEtag != null) {
                sink.onEtag(newEtag);
            }
            try (InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[1 << 16];
                long done = have;
                int r;
                while ((r = in.read(buf)) > 0) {
                    cancel.check();
                    out.write(buf, 0, r);
                    done += r;
                    if (progress != null) {
                        progress.onBytes(done, total);
                    }
                }
                out.getFD().sync();
                if (total > 0 && done < total) {
                    throw new IOException("connection closed after " + done + " of " + total + " bytes");
                }
            }
            return newEtag != null ? newEtag : etag;
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelToken.Cancelled();
            }
            throw e;
        } finally {
            cancel.onCancel(null);
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * The last n bytes of url, or null when the server does not serve ranges. Used to read a zip's
     * central directory (the list of files inside an XAPK) without downloading the whole bundle.
     */
    public static byte[] tail(String url, int n, CancelToken cancel) throws IOException {
        HttpURLConnection c = null;
        try {
            c = connect(url, "bytes=-" + n, null, cancel);
            if (c.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                return null;
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream(n);
            try (InputStream in = c.getInputStream()) {
                byte[] b = new byte[1 << 14];
                int r;
                while ((r = in.read(b)) > 0 && buf.size() <= n) {
                    buf.write(b, 0, r);
                }
            }
            return buf.toByteArray();
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelToken.Cancelled();
            }
            throw e;
        } finally {
            cancel.onCancel(null);
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static HttpURLConnection connect(String url, String range, String ifRange,
            final CancelToken cancel) throws IOException {
        String current = url;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            cancel.check();
            final HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) {
                c.setRequestProperty("Range", range);
            }
            if (ifRange != null) {
                c.setRequestProperty("If-Range", ifRange);
            }
            cancel.onCancel(new Runnable() {
                @Override
                public void run() {
                    c.disconnect();
                }
            });
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) {
                    throw new IOException("redirect without a Location");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    /** "bytes start-end/total" as {start, end, total}; total is -1 for "*". */
    static long[] contentRange(String header) {
        if (header == null || !header.startsWith("bytes ")) {
            return null;
        }
        try {
            String spec = header.substring(6).trim();
            int dash = spec.indexOf('-');
            int slash = spec.indexOf('/');
            if (dash < 0 || slash < dash) {
                return null;
            }
            long start = Long.parseLong(spec.substring(0, dash));
            long end = Long.parseLong(spec.substring(dash + 1, slash));
            String t = spec.substring(slash + 1);
            long total = "*".equals(t) ? -1 : Long.parseLong(t);
            return new long[] {start, end, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
