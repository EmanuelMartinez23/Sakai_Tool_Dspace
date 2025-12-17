package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.tool.api.SessionManager;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility to fetch, extract and index EPUB files by DSpace UUID into a per-user temp folder.
 * Mirrors the Moodle flow described by the user: authenticated fetch from DSpace 9, unzip, parse OPF,
 * build spine, and serve chapters via local servlet.
 */
@Slf4j
public class EpubStorageUtil {

    public static class Index {
        public String uuid;
        public String title;
        public String opfPath; // relative to bookDir
        public String opfDir;  // relative dir
        public List<String> manifestOrder = new ArrayList<>(); // ids order
        public Map<String, String> manifest = new LinkedHashMap<>(); // id -> href (rel)
        public List<String> spine = new ArrayList<>(); // hrefs (rel)
        public long createdAt;
    }

    private final ServerConfigurationService config;
    private final SessionManager sessionManager;

    public EpubStorageUtil() {
        this.config = ComponentManager.get(ServerConfigurationService.class);
        this.sessionManager = ComponentManager.get(SessionManager.class);
    }

    public String getCurrentUserId() {
        return sessionManager != null && sessionManager.getCurrentSessionUserId() != null
                ? sessionManager.getCurrentSessionUserId() : "anon";
    }

    public File getBaseDir() {
        String base = System.getProperty("java.io.tmpdir", "/tmp");
        return new File(base, "sakai-assignment-epub-books");
    }

    public File getUserDir(String userId) {
        return new File(getBaseDir(), safeName(userId));
    }

    public File getBookDir(String userId, String uuid) {
        return new File(getUserDir(userId), safeName(uuid));
    }

    private String safeName(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static String safeRelPath(String rel) {
        if (rel == null) return null;
        String r = rel.replace('\\', '/');
        // remove leading slashes
        while (r.startsWith("/")) r = r.substring(1);
        // normalize .. and .
        Deque<String> stack = new ArrayDeque<>();
        for (String part : r.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!stack.isEmpty()) stack.removeLast(); continue; }
            stack.addLast(part);
        }
        return String.join("/", stack);
    }

    public long getTtlMillis() {
        int hours = config.getInt("assignment.epub.ttlHours", 24);
        return hours * 3600_000L;
    }

    public long getMaxSizeBytes() {
        long mb = config.getInt("assignment.epub.maxSizeMB", 200);
        return mb * 1024L * 1024L;
    }

    public void lazyCleanupUser(String userId) {
        File userDir = getUserDir(userId);
        long ttl = getTtlMillis();
        long now = System.currentTimeMillis();
        File[] subs = userDir.listFiles();
        if (subs == null) return;
        for (File f : subs) {
            try {
                if (now - f.lastModified() > ttl) deleteRecursive(f);
            } catch (Exception ignore) {}
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) for (File c : list) deleteRecursive(c);
        }
        try { Files.deleteIfExists(f.toPath()); } catch (IOException ignore) {}
    }

    public Index ensureExtracted(String userId, String uuid) throws IOException {
        lazyCleanupUser(userId);
        File bookDir = getBookDir(userId, uuid);
        if (!bookDir.exists() && !bookDir.mkdirs()) throw new IOException("Cannot create book dir");
        File indexFile = new File(bookDir, "index.json");
        if (indexFile.exists()) {
            try (InputStream in = new FileInputStream(indexFile)) {
                Index idx = new ObjectMapper().readValue(in, Index.class);
                bookDir.setLastModified(System.currentTimeMillis());
                return idx;
            } catch (Exception e) {
                log.warn("[EPUB] Failed to read index.json, re-extracting: {}", e.toString());
            }
        }

        // Download EPUB zip
        File zip = new File(bookDir, "book.epub");
        downloadFromDSpace(uuid, zip, getMaxSizeBytes());

        // Extract
        extractZip(zip, bookDir);

        // Parse container.xml and OPF
        File container = new File(bookDir, "META-INF/container.xml");
        if (!container.exists()) throw new IOException("META-INF/container.xml not found");

        String opfRel = findOpfPath(container);
        if (opfRel == null) throw new IOException("OPF path not found in container.xml");
        opfRel = safeRelPath(opfRel);
        File opf = new File(bookDir, opfRel);
        if (!opf.exists()) throw new IOException("OPF file does not exist");

        Index idx = parseOpf(bookDir, opfRel, opf);
        idx.uuid = uuid;
        idx.createdAt = System.currentTimeMillis();

        // Write index
        try (OutputStream out = new FileOutputStream(indexFile)) {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, idx);
        }
        bookDir.setLastModified(System.currentTimeMillis());
        return idx;
    }

    private void extractZip(File zip, File destDir) throws IOException {
        // clean dest except zip and index.json
        File[] list = destDir.listFiles();
        if (list != null) for (File f : list) {
            if (f.getName().equals(zip.getName()) || f.getName().equals("index.json")) continue;
            deleteRecursive(f);
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = safeRelPath(entry.getName());
                if (name.isEmpty()) continue;
                File out = new File(destDir, name);
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs()) throw new IOException("Cannot create dir " + out);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot mkdirs " + parent);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int r; while ((r = zis.read(buffer)) != -1) os.write(buffer, 0, r);
                    }
                }
            }
        }
    }

    private String findOpfPath(File containerXml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(containerXml);
            NodeList rootfiles = doc.getElementsByTagName("rootfile");
            for (int i = 0; i < rootfiles.getLength(); i++) {
                Node n = rootfiles.item(i);
                Node attr = n.getAttributes() != null ? n.getAttributes().getNamedItem("full-path") : null;
                if (attr != null) return attr.getNodeValue();
            }
            return null;
        } catch (Exception e) {
            throw new IOException("Invalid container.xml: " + e.getMessage(), e);
        }
    }

    private Index parseOpf(File bookDir, String opfRel, File opfFile) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(opfFile);

            Index idx = new Index();
            idx.opfPath = opfRel;
            String opfDir = opfRel.contains("/") ? opfRel.substring(0, opfRel.lastIndexOf('/')) : "";
            idx.opfDir = opfDir;

            // title
            NodeList titleNodes = doc.getElementsByTagName("dc:title");
            if (titleNodes.getLength() == 0) titleNodes = doc.getElementsByTagName("title");
            if (titleNodes.getLength() > 0) idx.title = titleNodes.item(0).getTextContent();

            // manifest items
            NodeList items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node it = items.item(i);
                Node idAttr = it.getAttributes() != null ? it.getAttributes().getNamedItem("id") : null;
                Node hrefAttr = it.getAttributes() != null ? it.getAttributes().getNamedItem("href") : null;
                if (idAttr != null && hrefAttr != null) {
                    String id = idAttr.getNodeValue();
                    String href = safeRelPath(hrefAttr.getNodeValue());
                    idx.manifest.put(id, href);
                    idx.manifestOrder.add(id);
                }
            }

            // spine
            NodeList sp = doc.getElementsByTagName("itemref");
            for (int i = 0; i < sp.getLength(); i++) {
                Node ref = sp.item(i);
                Node idref = ref.getAttributes() != null ? ref.getAttributes().getNamedItem("idref") : null;
                if (idref != null) {
                    String mid = idref.getNodeValue();
                    String href = idx.manifest.get(mid);
                    if (href != null) idx.spine.add(resolveRel(idx.opfDir, href));
                }
            }
            if (idx.spine.isEmpty()) {
                // fallback: first html/xhtml from manifest order
                for (String mid : idx.manifestOrder) {
                    String href = idx.manifest.get(mid);
                    if (href != null && href.toLowerCase(Locale.ROOT).matches(".*\\.(x?html?)$")) {
                        idx.spine.add(resolveRel(idx.opfDir, href));
                        break;
                    }
                }
            }
            // also resolve manifest to be relative from root
            LinkedHashMap<String, String> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : idx.manifest.entrySet()) {
                resolved.put(e.getKey(), resolveRel(idx.opfDir, e.getValue()));
            }
            idx.manifest = resolved;

            return idx;
        } catch (Exception e) {
            throw new IOException("Invalid OPF: " + e.getMessage(), e);
        }
    }

    private String resolveRel(String base, String rel) {
        String p = (base == null || base.isEmpty()) ? safeRelPath(rel) : safeRelPath(base + "/" + rel);
        return p;
    }

    private void downloadFromDSpace(String uuid, File out, long maxBytes) throws IOException {
        String api = config.getString("assignment.epub.dspace.server", null);
        String email = config.getString("assignment.epub.dspace.email", null);
        String password = config.getString("assignment.epub.dspace.password", null);
        if (api == null || email == null || password == null) {
            throw new IOException("DSpace credentials not configured (assignment.epub.dspace.*)");
        }
        api = trimSlash(api);

        String token = authenticate(api, email, password);
        if (token == null || token.isEmpty()) throw new IOException("Cannot authenticate to DSpace");

        String url;
        if (api.endsWith("/server/api") || api.endsWith("/api")) {
            url = api + "/core/bitstreams/" + uuid + "/content";
        } else {
            url = api + "/bitstreams/" + uuid + "/download";
        }

        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setInstanceFollowRedirects(true);
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("Authorization", "Bearer " + token);
        con.setRequestProperty("Accept", "*/*");

        int code = con.getResponseCode();
        if (code >= 400) throw new IOException("HTTP " + code + " from DSpace");

        long contentLength = con.getContentLengthLong();
        if (contentLength > 0 && contentLength > maxBytes) throw new IOException("EPUB too large: " + contentLength);

        try (InputStream in = con.getInputStream()) {
            Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String authenticate(String apiBase, String email, String password) throws IOException {
        // GET csrf
        HttpURLConnection c1 = (HttpURLConnection) new URL(apiBase + "/security/csrf").openConnection();
        c1.setRequestMethod("GET");
        c1.setInstanceFollowRedirects(false);
        int code1 = c1.getResponseCode();
        String setCookie = c1.getHeaderField("Set-Cookie");
        c1.disconnect();
        if (setCookie == null) throw new IOException("No CSRF cookie (HTTP " + code1 + ")");
        String xsrf = extractXsrfCookie(setCookie);
        if (xsrf == null) throw new IOException("XSRF token not found");

        // POST login
        HttpURLConnection c2 = (HttpURLConnection) new URL(apiBase + "/authn/login").openConnection();
        c2.setRequestMethod("POST");
        c2.setInstanceFollowRedirects(false);
        c2.setDoOutput(true);
        c2.setRequestProperty("X-XSRF-TOKEN", xsrf);
        c2.setRequestProperty("Cookie", "DSPACE-XSRF-COOKIE=" + xsrf);
        c2.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = "user=" + urlEncode(email) + "&password=" + urlEncode(password);
        try (OutputStream os = c2.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        String auth = c2.getHeaderField("Authorization");
        c2.disconnect();
        if (auth == null || !auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) throw new IOException("No Bearer token");
        return auth.substring(7).trim();
    }

    private static String extractXsrfCookie(String setCookie) {
        if (setCookie == null) return null;
        // find DSPACE-XSRF-COOKIE=value;...
        String[] parts = setCookie.split(";\\s*");
        for (String p : parts) {
            if (p.startsWith("DSPACE-XSRF-COOKIE=")) return p.substring("DSPACE-XSRF-COOKIE=".length());
        }
        return null;
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String trimSlash(String s) {
        if (s == null) return null;
        if (s.endsWith("/")) return s.substring(0, s.length()-1);
        return s;
    }

    public static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")) return "text/html;charset=UTF-8";
        if (n.endsWith(".css")) return "text/css;charset=UTF-8";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".otf")) return "font/otf";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    public Index readIndex(String userId, String uuid) throws IOException {
        File indexFile = new File(getBookDir(userId, uuid), "index.json");
        if (!indexFile.exists()) return null;
        try (InputStream in = new FileInputStream(indexFile)) {
            return new ObjectMapper().readValue(in, Index.class);
        }
    }
}
