package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.tool.api.SessionManager;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

/**
 * Path-based static file server for extracted EPUBs, so that relative URLs inside chapters resolve naturally.
 *
 * URL pattern: /epub/book/*
 * Examples:
 *   /epub/book/{uuid}/Text/chapter1.xhtml
 *   /epub/book/{uuid}/Images/cover.jpg
 *
 * Security:
 *   - Requires authenticated Sakai session
 *   - Serves only from the current user's extracted workspace for the given UUID
 *   - Normalizes paths to prevent traversal
 *
 * Supports Range requests and sets appropriate content types.
 */
@Slf4j
public class EpubBookServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        SessionManager sm = ComponentManager.get(SessionManager.class);
        String userId = sm != null ? sm.getCurrentSessionUserId() : null;
        if (userId == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String pathInfo = req.getPathInfo(); // expected: /{uuid}/path/inside/epub
        if (pathInfo == null || pathInfo.length() <= 1) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing path");
            return;
        }
        // Trim leading '/'
        String trimmed = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        int firstSlash = trimmed.indexOf('/') ;
        if (firstSlash <= 0) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing uuid or file path");
            return;
        }
        String uuid = trimmed.substring(0, firstSlash);
        String relPathRaw = trimmed.substring(firstSlash + 1);
        if (uuid.isEmpty() || relPathRaw.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing uuid or file path");
            return;
        }
        // Normalize relative path inside the EPUB
        String fileRel = EpubStorageUtil.safeRelPath(relPathRaw);

        EpubStorageUtil util = new EpubStorageUtil();
        // Ensure the EPUB is extracted (and update mtime)
        try {
            util.ensureExtracted(userId, uuid);
        } catch (IOException e) {
            log.warn("[EPUB] ensureExtracted failed: {}", e.toString());
            resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, e.getMessage());
            return;
        }

        File bookDir = util.getBookDir(userId, uuid);
        File target = new File(bookDir, fileRel);

        // Path traversal protection: must stay under bookDir
        String basePath = bookDir.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(basePath + File.separator) && !targetPath.equals(basePath)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Path outside book directory");
            return;
        }
        if (!target.exists() || !target.isFile()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contentType = EpubStorageUtil.contentType(target.getName());
        resp.setHeader("Accept-Ranges", "bytes");
        resp.setHeader("Cache-Control", "private, max-age=86400");
        resp.setContentType(contentType);

        String range = req.getHeader("Range");
        long length = target.length();

        try (RandomAccessFile raf = new RandomAccessFile(target, "r"); OutputStream out = resp.getOutputStream()) {
            if (range != null && range.startsWith("bytes=")) {
                long start = 0, end = length - 1;
                String spec = range.substring("bytes=".length());
                String part = spec.split(",")[0].trim();
                if (part.startsWith("-")) {
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

    private long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
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
}
