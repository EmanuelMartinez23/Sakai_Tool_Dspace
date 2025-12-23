package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple file cache + whitelist fetcher for EPUB downloads.
 */
@Slf4j
public class EpubCacheService {

    private final File cacheDir;
    private final long ttlMillis;
    private final long maxBytes;
    private final Set<String> allowedHosts;

    public EpubCacheService() {
        ServerConfigurationService scs = ComponentManager.get(ServerConfigurationService.class);
        String baseDir = scs != null ? scs.getString("assignment.epub.cache.dir", null) : null;
        if (baseDir == null || baseDir.trim().isEmpty()) {
            baseDir = System.getProperty("java.io.tmpdir") + File.separator + "sakai-epub-cache";
        }
        this.cacheDir = new File(baseDir);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            log.warn("[EPUB] Could not create cache dir: {}", cacheDir.getAbsolutePath());
        }
        long ttlSec = scs != null ? scs.getInt("assignment.epub.cache.ttl.seconds", 86400) : 86400; // 1 day
        this.ttlMillis = Math.max(60, ttlSec) * 1000L;
        this.maxBytes = scs != null ? scs.getLong("assignment.epub.max.bytes", 50L * 1024 * 1024) : 50L * 1024 * 1024; // 50MB
        String hosts = scs != null ? scs.getString("assignment.epub.allowedHosts", "127.0.0.1,localhost,192.168.1.27") : "127.0.0.1,localhost,192.168.1.27";
        Set<String> set = new HashSet<>();
        for (String h : hosts.split(",")) {
            String s = h.trim();
            if (!s.isEmpty()) set.add(s.toLowerCase());
        }
        this.allowedHosts = set;
        log.info("[EPUB] Cache dir: {}, ttlSec: {}, maxBytes: {}, allowedHosts: {}", cacheDir.getAbsolutePath(), ttlSec, maxBytes, allowedHosts);
    }

    public boolean isAllowed(URL u) {
        String host = (u.getHost() == null ? "" : u.getHost().toLowerCase());
        return allowedHosts.contains(host);
    }

    public File getOrFetch(String sourceUrl) throws IOException {
        URL url = new URL(sourceUrl);
        if (!isAllowed(url)) {
            throw new IOException("Host not allowed: " + url.getHost());
        }
        String key = sha256(sourceUrl);
        File file = new File(cacheDir, key + ".epub");
        if (file.exists() && !isExpired(file)) {
            return file;
        }
        // fetch and write atomically
        File tmp = new File(cacheDir, key + ".part");
        download(url, tmp);
        if (tmp.length() <= 0) {
            tmp.delete();
            throw new IOException("Downloaded file is empty");
        }
        if (tmp.length() > maxBytes) {
            tmp.delete();
            throw new IOException("Downloaded file exceeds max size: " + tmp.length());
        }
        if (file.exists()) file.delete();
        if (!tmp.renameTo(file)) {
            // fallback copy
            try (java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                IOUtils.copy(in, out);
            }
            tmp.delete();
        }
        touch(file);
        cleanupExpired();
        return file;
    }

    private void download(URL url, File out) throws IOException {
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Sakai-Assignment-EPUB-Proxy");
        int code = con.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        long contentLen = con.getContentLengthLong();
        if (contentLen > 0 && contentLen > maxBytes) {
            throw new IOException("Remote content too large: " + contentLen);
        }
        try (InputStream is = con.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            long copied = IOUtils.copyLarge(is, fos, 0, maxBytes + 1);
            if (copied > maxBytes) {
                throw new IOException("Downloaded exceeds max size: " + copied);
            }
        } finally {
            con.disconnect();
        }
    }

    private boolean isExpired(File f) {
        long age = System.currentTimeMillis() - f.lastModified();
        return age > ttlMillis;
    }

    private void touch(File f) {
        // update mtime to now
        //noinspection ResultOfMethodCallIgnored
        f.setLastModified(System.currentTimeMillis());
    }

    public void cleanupExpired() {
        File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".epub") || name.endsWith(".part"));
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File f : files) {
            if (now - f.lastModified() > ttlMillis) {
                if (!f.delete()) {
                    log.debug("[EPUB] Could not delete expired file: {}", f.getAbsolutePath());
                }
            }
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            // fallback
            return Integer.toHexString(s.hashCode());
        }
    }
}
