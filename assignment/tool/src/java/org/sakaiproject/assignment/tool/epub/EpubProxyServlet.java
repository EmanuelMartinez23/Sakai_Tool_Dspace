package org.sakaiproject.assignment.tool.epub;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Simple proxy to fetch EPUB files from an external URL, cache them locally for 24h,
 * and serve them from the same origin with HTTP Range support.
 */
public class EpubProxyServlet extends HttpServlet {

    private static final long TTL_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_SIZE_BYTES = 200L * 1024 * 1024; // 200 MB

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String srcParam = req.getParameter("src");
        if (srcParam == null || srcParam.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing src parameter");
            return;
        }
        String src = URLDecoder.decode(srcParam, StandardCharsets.UTF_8.name());
        // Basic validation
        if (!(src.startsWith("http://") || src.startsWith("https://"))) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Unsupported URL scheme");
            return;
        }

        File cacheDir = getCacheDir();
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Cannot create cache directory");
            return;
        }

        // Lazy cleanup
        cleanupOld(cacheDir, TTL_MS);

        String key = sha256(src);
        File file = new File(cacheDir, key + ".epub");

        // Refresh cache if not exists or expired
        if (!file.exists() || isExpired(file, TTL_MS)) {
            try {
                downloadToFile(src, file, MAX_SIZE_BYTES);
            } catch (IOException ex) {
                if (file.exists()) file.delete();
                resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Failed to download EPUB: " + ex.getMessage());
                return;
            }
        }

        // Serve with Range support
        serveFileWithRange(file, req, resp);
    }

    private File getCacheDir() {
        String base = System.getProperty("java.io.tmpdir", "/tmp");
        return new File(base, "sakai-assignment-epub-cache");
    }

    private boolean isExpired(File f, long ttlMs) {
        long age = System.currentTimeMillis() - f.lastModified();
        return age > ttlMs;
    }

    private void cleanupOld(File dir, long ttlMs) {
        File[] list = dir.listFiles();
        if (list == null) return;
        long now = System.currentTimeMillis();
        for (File f : list) {
            if (!f.isFile()) continue;
            if (now - f.lastModified() > ttlMs) {
                try { f.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private void downloadToFile(String src, File outFile, long maxSize) throws IOException {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            URL url = new URL(src);
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "Sakai-Assignment-EPUB-Proxy");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && !loc.isEmpty()) {
                    if (conn != null) conn.disconnect();
                    url = new URL(loc);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(READ_TIMEOUT_MS);
                }
            }
            int response = conn.getResponseCode();
            if (response != HttpServletResponse.SC_OK) {
                throw new IOException("Remote server HTTP " + response);
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > 0 && contentLength > maxSize) {
                throw new IOException("File too large: " + contentLength);
            }

            in = new BufferedInputStream(conn.getInputStream());
            out = new BufferedOutputStream(new FileOutputStream(outFile));
            byte[] buf = new byte[8192];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > maxSize) {
                    throw new IOException("File exceeds maximum size");
                }
                out.write(buf, 0, r);
            }
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
        // Touch lastModified to now
        // no-op: writing sets it already
    }

    private void serveFileWithRange(File file, HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String range = req.getHeader("Range");
        long length = file.length();
        resp.setHeader("Accept-Ranges", "bytes");
        resp.setHeader("Cache-Control", "private, max-age=86400");
        resp.setContentType("application/epub+zip");

        try (RandomAccessFile raf = new RandomAccessFile(file, "r"); OutputStream out = resp.getOutputStream()) {
            if (range != null && range.startsWith("bytes=")) {
                long start = 0, end = length - 1;
                // Support only a single range
                String spec = range.substring("bytes=".length());
                String[] parts = spec.split(",");
                String part = parts[0].trim();
                if (part.startsWith("-")) {
                    // suffix length
                    long suffix = parseLongSafe(part.substring(1), 0);
                    if (suffix > 0) start = Math.max(0, length - suffix);
                } else if (part.endsWith("-")) {
                    start = parseLongSafe(part.substring(0, part.length() - 1), 0);
                } else {
                    String[] se = part.split("-");
                    start = parseLongSafe(se[0], 0);
                    end = (se.length > 1) ? parseLongSafe(se[1], end) : end;
                }
                if (start < 0) start = 0;
                if (end >= length) end = length - 1;
                if (start > end) {
                    resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    resp.setHeader("Content-Range", "bytes */" + length);
                    return;
                }
                long contentLen = end - start + 1;
                resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
                resp.setHeader("Content-Length", String.valueOf(contentLen));
                copyRange(raf, out, start, contentLen);
            } else {
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.setHeader("Content-Length", String.valueOf(length));
                copyRange(raf, out, 0, length);
            }
        }
    }

    private void copyRange(RandomAccessFile raf, OutputStream out, long start, long len) throws IOException {
        raf.seek(start);
        byte[] buf = new byte[8192];
        long remaining = len;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int r = raf.read(buf, 0, toRead);
            if (r == -1) break;
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
