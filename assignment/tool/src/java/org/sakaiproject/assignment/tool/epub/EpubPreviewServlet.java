package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;

/**
 * Legacy endpoint kept for backward compatibility.
 * Immediately redirects to the new reader UI at /epub/read within the same placement.
 * URL: /epub/preview?uuid=<uuid>&nav=<index>
 */
@Slf4j
public class EpubPreviewServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        SessionManager sm = ComponentManager.get(SessionManager.class);
        String userId = sm != null ? sm.getCurrentSessionUserId() : null;
        if (userId == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        String uuid = req.getParameter("uuid");
        if (uuid == null || uuid.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing uuid");
            return;
        }
        int nav = 0;
        try { nav = Integer.parseInt(req.getParameter("nav")); } catch (Exception ignore) {}

        // Prefer absolute redirect anchored to current placement: /portal/tool/{placementId}/epub/
        String target;
        try {
            String scheme = req.getScheme();
            String host = req.getServerName();
            int port = req.getServerPort();
            String origin = scheme + "://" + host + ((port == 80 || port == 443) ? "" : (":" + port));

            String placementId = null;
            try {
                ToolManager tm = ComponentManager.get(ToolManager.class);
                if (tm != null && tm.getCurrentPlacement() != null) {
                    placementId = tm.getCurrentPlacement().getId();
                }
            } catch (Throwable ignore) { }

            if (placementId == null) {
                String uri = req.getRequestURI();
                // Try to extract placement from /portal/site/{site}/tool/{placement}/...
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("/portal/(?:site/[^/]+/)?tool/([^/]+)/")
                        .matcher(uri);
                if (m.find()) {
                    placementId = m.group(1);
                }
            }
            // Try Referer header if still unknown
            if (placementId == null) {
                String ref = req.getHeader("Referer");
                if (ref != null) {
                    java.util.regex.Matcher mr = java.util.regex.Pattern
                            .compile("/portal/(?:site/[^/]+/)?tool/([^/]+)(?:/|\\?)")
                            .matcher(ref);
                    if (mr.find()) placementId = mr.group(1);
                }
            }

            if (placementId != null) {
                String baseDir = origin + "/portal/tool/" + placementId + "/epub/"; // ends with /
                target = baseDir + "view?uuid=" + url(uuid) + (nav > 0 ? ("&nav=" + nav) : "");
            } else {
                // Fallback: relative to current path
                String uri = req.getRequestURI();
                int lastSlash = uri.lastIndexOf('/') + 1;
                String baseDir = lastSlash > 0 ? uri.substring(0, lastSlash) : "/";
                target = baseDir + "view?uuid=" + url(uuid) + (nav > 0 ? ("&nav=" + nav) : "");
            }
        } catch (Exception ex) {
            // Last resort: relative redirect
            String uri = req.getRequestURI();
            int lastSlash = uri.lastIndexOf('/') + 1;
            String baseDir = lastSlash > 0 ? uri.substring(0, lastSlash) : "/";
            target = baseDir + "view?uuid=" + url(uuid) + (nav > 0 ? ("&nav=" + nav) : "");
        }

        // 303 See Other avoids POST replays
        resp.setStatus(HttpServletResponse.SC_SEE_OTHER);
        resp.setHeader("Location", target);
    }

    private String url(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
