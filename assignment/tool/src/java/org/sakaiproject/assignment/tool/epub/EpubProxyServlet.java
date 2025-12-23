package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
public class EpubProxyServlet extends HttpServlet {

    private transient EpubCacheService cacheService;

    @Override
    public void init() throws ServletException {
        super.init();
        cacheService = new EpubCacheService();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String urlParam = req.getParameter("url");
        if (urlParam == null || urlParam.trim().isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'url' parameter");
            return;
        }
        String sourceUrl = URLDecoder.decode(urlParam, StandardCharsets.UTF_8.name());
        try {
            log.info("[EPUB] Proxy request: {}", sourceUrl);
            File f = cacheService.getOrFetch(sourceUrl);
            if (!f.exists()) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found");
                return;
            }
            resp.setHeader("X-EPUB-PROXY", "hit");
            resp.setContentType("application/epub+zip");
            resp.setHeader("Content-Disposition", "inline; filename=book.epub");
            resp.setHeader("Cache-Control", "private, max-age=300");
            resp.setDateHeader("Last-Modified", f.lastModified());
            resp.setContentLengthLong(f.length());
            try (InputStream in = new BufferedInputStream(new FileInputStream(f));
                 OutputStream out = new BufferedOutputStream(resp.getOutputStream())) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
                out.flush();
            }
            log.info("[EPUB] Proxy served: {} bytes", f.length());
        } catch (IOException ex) {
            log.warn("[EPUB] Proxy error for {}: {}", sourceUrl, ex.toString());
            if (ex.getMessage() != null && ex.getMessage().contains("Host not allowed")) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Host not allowed");
            } else if (ex.getMessage() != null && ex.getMessage().contains("HTTP ")) {
                resp.sendError(HttpServletResponse.SC_BAD_GATEWAY, ex.getMessage());
            } else if (ex.getMessage() != null && ex.getMessage().contains("exceeds max size")) {
                resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ex.getMessage());
            } else {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Proxy failure");
            }
        }
    }
}
