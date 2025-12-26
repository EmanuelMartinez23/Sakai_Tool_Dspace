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

    private static final String UA = "Sakai-Assignment-EPUB-Proxy";

    private final File cacheDir;
    private final long ttlMillis;
    private final long maxBytes;
    private final Set<String> allowedHosts;

    // DSpace integration (optional)
    private final String dspaceApiBase;   // e.g. http://host:8080/server/api
    private final String dspaceFrontBase; // e.g. http://host:4000
    private final String dspaceEmail;
    private final String dspacePassword;

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
        // Read DSpace properties and add their hosts to whitelist automatically
        this.dspaceApiBase = scs != null ? trimTrailingSlash(scs.getString("dspace.api.base")) : null;
        this.dspaceFrontBase = scs != null ? trimTrailingSlash(scs.getString("dspace.front.base")) : null;
        this.dspaceEmail = scs != null ? scs.getString("dspace.auth.email") : null;
        this.dspacePassword = scs != null ? scs.getString("dspace.auth.password") : null;
        try { if (dspaceApiBase != null) set.add(new URL(dspaceApiBase).getHost().toLowerCase()); } catch (Exception ignore) {}
        try { if (dspaceFrontBase != null) set.add(new URL(dspaceFrontBase).getHost().toLowerCase()); } catch (Exception ignore) {}
        this.allowedHosts = set;
        log.info("[EPUB] Cache dir: {}, ttlSec: {}, maxBytes: {}, allowedHosts: {}", cacheDir.getAbsolutePath(), ttlSec, maxBytes, allowedHosts);
        if (dspaceApiBase != null || dspaceFrontBase != null) {
            log.info("[EPUB] DSpace configured apiBase={} frontBase={} emailPresent={} ", dspaceApiBase, dspaceFrontBase, (dspaceEmail != null && !dspaceEmail.isEmpty()));
        }
    }

    public boolean isAllowed(URL u) {
        String host = (u.getHost() == null ? "" : u.getHost().toLowerCase());
        return allowedHosts.contains(host);
    }

    /** Return cached file object (may not exist) for a source URL. */
    public File getCachedFile(String sourceUrl) throws IOException {
        String key = sha256(sourceUrl);
        return new File(cacheDir, key + ".epub");
    }

    /** Invalidate cached file for a source URL. */
    public void invalidate(String sourceUrl) throws IOException {
        File f = getCachedFile(sourceUrl);
        if (f.exists() && !f.delete()) {
            log.debug("[EPUB] Could not delete cached file: {}", f.getAbsolutePath());
        }
    }

    public File getOrFetch(String sourceUrl) throws IOException {
        return getOrFetch(sourceUrl, false);
    }

    public File getOrFetch(String sourceUrl, boolean forceRefresh) throws IOException {
        URL url = new URL(sourceUrl);
        if (!isAllowed(url)) {
            throw new IOException("Host not allowed: " + url.getHost());
        }
        String key = sha256(sourceUrl);
        File file = new File(cacheDir, key + ".epub");
        if (!forceRefresh && file.exists() && !isExpired(file) && isZipFile(file)) {
            return file;
        }
        // fetch and write atomically
        File tmp = new File(cacheDir, key + ".part");
        download(url, tmp);
        if (tmp.length() <= 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Downloaded file is empty");
        }
        if (tmp.length() > maxBytes) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Downloaded file exceeds max size: " + tmp.length());
        }
        // Validate ZIP magic before promoting to cache
        if (!isZipFile(tmp)) {
            long len = tmp.length();
            byte[] head = readHead(tmp, 8);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Upstream did not return EPUB (ZIP). firstBytes=" + bytesToHex(head) + ", length=" + len);
        }
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
        if (!tmp.renameTo(file)) {
            // fallback copy
            try (java.io.FileInputStream in = new java.io.FileInputStream(tmp);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                IOUtils.copy(in, out);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        touch(file);
        cleanupExpired();
        return file;
    }

    private void download(URL url, File out) throws IOException {
        // If configured for DSpace and this is a front download URL, use authenticated DSpace API fetch
        if (dspaceApiBase != null && dspaceFrontBase != null && isDSpaceFrontDownload(url)) {
            downloadFromDSpace(url, out);
            return;
        }
        // Otherwise plain download
        downloadPlain(url, out);
    }

    private void downloadPlain(URL url, File out) throws IOException {
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", UA);
        con.setRequestProperty("Accept", "application/epub+zip,application/octet-stream;q=0.9,*/*;q=0.1");
        con.setRequestProperty("Accept-Encoding", "identity");
        int code = con.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + " from " + url);
        }
        long contentLen = con.getContentLengthLong();
        if (contentLen > 0 && contentLen > maxBytes) {
            throw new IOException("Remote content too large: " + contentLen);
        }
        URL finalUrl = con.getURL();
        if (!isAllowed(finalUrl)) {
            throw new IOException("Redirected host not allowed: " + finalUrl.getHost());
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

    private boolean isDSpaceFrontDownload(URL url) {
        try {
            URL fb = new URL(dspaceFrontBase);
            if (!fb.getHost().equalsIgnoreCase(url.getHost())) return false;
            String path = url.getPath();
            if (path == null) return false;
            String[] seg = path.split("/");
            // Expect: [ , bitstreams, {uuid}, download]
            if (seg.length < 4) return false;
            if (!"bitstreams".equals(seg[1])) return false;
            if (!"download".equals(seg[3])) return false;
            String uuid = seg[2];
            return uuid != null && uuid.length() >= 32; // loose check
        } catch (Exception e) {
            return false;
        }
    }

    private String extractBitstreamUuid(String path) {
        if (path == null) return null;
        // Expected: /bitstreams/{uuid}/download
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if ("bitstreams".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private void downloadFromDSpace(URL frontDownload, File out) throws IOException {
        if (dspaceEmail == null || dspacePassword == null || dspaceApiBase == null) {
            // Fall back to plain if missing creds
            downloadPlain(frontDownload, out);
            return;
        }
        // Map to API URL
        String uuid = extractBitstreamUuid(frontDownload.getPath());
        if (uuid == null) {
            downloadPlain(frontDownload, out);
            return;
        }
        URL apiCsrf = new URL(dspaceApiBase + "/security/csrf");
        HttpURLConnection c1 = (HttpURLConnection) apiCsrf.openConnection();
        c1.setConnectTimeout(10000);
        c1.setReadTimeout(15000);
        c1.setInstanceFollowRedirects(false);
        c1.setRequestProperty("User-Agent", UA);
        c1.setRequestProperty("Accept", "application/json");
        int code1 = c1.getResponseCode();
        String csrf = c1.getHeaderField("X-CSRF-TOKEN");
        String cookie1 = c1.getHeaderField("Set-Cookie");
        c1.disconnect();
        if (code1 >= 400 || csrf == null || csrf.isEmpty() || cookie1 == null || cookie1.isEmpty()) {
            // Try unauthenticated plain as a fallback
            downloadPlain(frontDownload, out);
            return;
        }

        // Login
        URL apiLogin = new URL(dspaceApiBase + "/authn/login");
        HttpURLConnection c2 = (HttpURLConnection) apiLogin.openConnection();
        c2.setConnectTimeout(10000);
        c2.setReadTimeout(20000);
        c2.setInstanceFollowRedirects(false);
        c2.setDoOutput(true);
        c2.setRequestMethod("POST");
        c2.setRequestProperty("User-Agent", UA);
        c2.setRequestProperty("X-XSRF-TOKEN", csrf);
        c2.setRequestProperty("Cookie", cookie1);
        c2.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        String body = "email=" + urlEnc(dspaceEmail) + "&password=" + urlEnc(dspacePassword);
        try (java.io.OutputStream os = c2.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code2 = c2.getResponseCode();
        String cookie2 = c2.getHeaderField("Set-Cookie");
        c2.disconnect();
        if (code2 >= 400) {
            throw new IOException("HTTP " + code2 + " during DSpace login");
        }
        // Merge cookies
        String cookies = mergeCookies(cookie1, cookie2);

        // GET content
        URL apiContent = new URL(dspaceApiBase + "/core/bitstreams/" + uuid + "/content");
        HttpURLConnection c3 = (HttpURLConnection) apiContent.openConnection();
        c3.setConnectTimeout(15000);
        c3.setReadTimeout(30000);
        c3.setInstanceFollowRedirects(true);
        c3.setRequestProperty("User-Agent", UA);
        c3.setRequestProperty("Accept", "application/epub+zip,application/octet-stream;q=0.9,*/*;q=0.1");
        c3.setRequestProperty("Accept-Encoding", "identity");
        c3.setRequestProperty("Cookie", cookies);
        int code3 = c3.getResponseCode();
        if (code3 >= 400) {
            throw new IOException("HTTP " + code3 + " from " + apiContent);
        }
        URL finalUrl = c3.getURL();
        if (!isAllowed(finalUrl)) {
            throw new IOException("Redirected host not allowed: " + finalUrl.getHost());
        }
        long contentLen = c3.getContentLengthLong();
        if (contentLen > 0 && contentLen > maxBytes) {
            throw new IOException("Remote content too large: " + contentLen);
        }
        try (InputStream is = c3.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
            long copied = IOUtils.copyLarge(is, fos, 0, maxBytes + 1);
            if (copied > maxBytes) {
                throw new IOException("Downloaded exceeds max size: " + copied);
            }
        } finally {
            c3.disconnect();
        }
    }

    private String trimTrailingSlash(String s) {
        if (s == null) return null;
        while (s.endsWith("/")) s = s.substring(0, s.length()-1);
        return s;
    }

    private String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }

    private String mergeCookies(String... setCookieHeaders) {
        StringBuilder sb = new StringBuilder();
        for (String h : setCookieHeaders) {
            if (h == null) continue;
            // take only name=value; ignore attributes
            String[] parts = h.split(",");
            for (String p : parts) {
                String[] nv = p.trim().split(";", 2);
                if (nv.length > 0 && nv[0].contains("=")) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(nv[0]);
                }
            }
        }
        return sb.toString();
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

    private static boolean isZipFile(File f) {
        byte[] head = readHead(f, 4);
        return head != null && head.length >= 2 && head[0] == 0x50 && head[1] == 0x4B; // 'P''K'
    }

    private static byte[] readHead(File f, int n) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[Math.max(0, n)];
            int r = fis.read(buf);
            if (r <= 0) return new byte[0];
            if (r < buf.length) return Arrays.copyOf(buf, r);
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private static String bytesToHex(byte[] a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : a) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
