package com.waze.wazeology;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.GZIPOutputStream;

/**
 * On-disk, rotating log store: an active {@code wazeology.log}
 * that every line is appended to and flushed immediately (a killed Waze process loses nothing), gzip
 * rotation once it passes {@link #ROTATE_BYTES}, and pruning of the oldest archives once they exceed
 * {@link #MAX_ARCHIVE_BYTES}. All IO runs on a single background thread and is wrapped so a disk error
 * only drops a log line rather than crashing the host app.
 */
final class LogStore {

    static final long ROTATE_BYTES = 1_000_000L;       // rotate the active file past ~1 MB
    static final long MAX_ARCHIVE_BYTES = 10_000_000L; // keep at most ~10 MB of gzip archives

    private static final String ACTIVE_NAME = "wazeology.log";
    private static final String ARCHIVE_PREFIX = "wazeology-";
    private static final String ARCHIVE_SUFFIX = ".log.gz";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final File dir;
    private final File active;
    private final SimpleDateFormat archiveStamp;
    private final ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "wazeology-log");
            t.setDaemon(true);
            return t;
        }
    });

    private Writer writer;
    private long activeBytes;

    LogStore(File dir) {
        this.dir = dir;
        this.active = new File(dir, ACTIVE_NAME);
        this.archiveStamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        this.archiveStamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        io.execute(new Runnable() {
            @Override
            public void run() {
                openWriter();
            }
        });
    }

    /** Append one already-stamped line (a trailing newline is added). Non-blocking; runs on the IO thread. */
    void append(final String line) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (writer == null) {
                        openWriter();
                    }
                    if (writer == null) {
                        return;
                    }
                    String out = line + "\n";
                    writer.write(out);
                    writer.flush();
                    activeBytes += out.getBytes(UTF8).length;
                    if (activeBytes > ROTATE_BYTES) {
                        rotate();
                    }
                } catch (Throwable ignored) {
                    // A disk error drops a log line; it must never take down Waze.
                }
            }
        });
    }

    /** Truncate the active file and delete every archive, so the on-screen "Clear" is honest on disk too. */
    void clear() {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    closeWriter();
                    File[] archives = listArchives();
                    for (File a : archives) {
                        a.delete();
                    }
                    active.delete();
                    activeBytes = 0;
                    openWriter();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * Read back the tail of the active file (up to {@code maxLines}) so the visible log survives a process
     * restart. Runs synchronously on the caller; the active file is bounded by {@link #ROTATE_BYTES}.
     */
    List<String> readActiveTail(int maxLines) {
        ArrayList<String> lines = new ArrayList<>();
        if (!active.isFile()) {
            return lines;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(active), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(reader);
        }
        if (lines.size() > maxLines) {
            return new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
        }
        return lines;
    }

    // ---- IO thread only ------------------------------------------------------------------------

    private void openWriter() {
        try {
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            activeBytes = active.isFile() ? active.length() : 0L;
            writer = new OutputStreamWriter(new FileOutputStream(active, true), UTF8);
        } catch (Throwable ignored) {
            writer = null;
        }
    }

    private void closeWriter() {
        closeQuietly(writer);
        writer = null;
    }

    private void rotate() {
        closeWriter();
        String stamp = archiveStamp.format(new Date());
        File tmp = new File(dir, ARCHIVE_PREFIX + stamp + ARCHIVE_SUFFIX + ".tmp");
        File archive = new File(dir, ARCHIVE_PREFIX + stamp + ARCHIVE_SUFFIX);
        boolean gzipped = gzip(active, tmp);
        if (gzipped && tmp.renameTo(archive)) {
            // Compressed copy is safely in place; drop the plain active file.
            active.delete();
        } else {
            tmp.delete();
        }
        activeBytes = 0;
        openWriter();
        prune();
    }

    private boolean gzip(File src, File dst) {
        if (!src.isFile()) {
            return false;
        }
        InputStream in = null;
        GZIPOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new GZIPOutputStream(new FileOutputStream(dst));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.finish();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(out);
            closeQuietly(in);
        }
    }

    /** Delete oldest archives until the combined size is under the cap; always keep the newest one. */
    private void prune() {
        File[] archives = listArchives();
        if (archives.length <= 1) {
            return;
        }
        // Oldest first: UTC timestamps in the name sort chronologically.
        Arrays.sort(archives, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        long total = 0;
        for (File a : archives) {
            total += a.length();
        }
        int i = 0;
        while (total > MAX_ARCHIVE_BYTES && i < archives.length - 1) {
            long len = archives[i].length();
            if (archives[i].delete()) {
                total -= len;
            }
            i++;
        }
    }

    private File[] listArchives() {
        File[] found = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX);
            }
        });
        return found == null ? new File[0] : found;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
