package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.component.cover.ComponentManager;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;

/**
 * Preview UI similar to Moodle: shows toolbar with prev/next, TOC select and an iframe
 * that points to the served chapter from the local extraction. Ensures the EPUB is
 * fetched and extracted for the current user.
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

        EpubStorageUtil util = new EpubStorageUtil();
        EpubStorageUtil.Index idx;
        try {
            idx = util.ensureExtracted(userId, uuid);
        } catch (IOException e) {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().println("<html><body><h3>Error</h3><pre>" + escapeHtml(e.getMessage()) + "</pre></body></html>");
            return;
        }

        String title = idx.title != null && !idx.title.isEmpty() ? idx.title : "EPUB";
        int count = idx.spine != null ? idx.spine.size() : 0;
        if (count == 0) {
            resp.setContentType("text/html;charset=UTF-8");
            resp.getWriter().println("<html><body><h3>Error</h3><p>No hay capítulos en el EPUB</p></body></html>");
            return;
        }
        if (nav < 0) nav = 0; if (nav >= count) nav = count - 1;
        String chapter = idx.spine.get(nav);

        // Use tool-relative URLs (no leading slash, no contextPath) so links stay within the tool placement under the Sakai portal
        String chapterUrl = "epub/serve?uuid=" + url(uuid) + "&file=" + url(chapter);
        String self = "epub/preview?uuid=" + url(uuid);

        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html;charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"es\">");
        out.println("<head>");
        out.println("<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        out.println("<title>" + escapeHtml(title) + " · Visor EPUB</title>");
        out.println("<style>html,body{height:100%;margin:0;padding:0;overflow:hidden;background:#f8f9fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}#viewer-container{position:fixed;inset:0;display:flex;flex-direction:column;background:#fff}#toolbar{padding:12px 16px;border-bottom:1px solid #dee2e6;display:flex;gap:12px;align-items:center;background:#fff;z-index:2}#iframewrap{position:relative;flex:1}#pageframe{position:absolute;inset:0;width:100%;height:100%;border:0;background:#fff}.btn{padding:6px 12px;border:1px solid #ced4da;background:#fff;border-radius:4px;cursor:pointer}.btn:disabled{opacity:.5;cursor:not-allowed}#status{margin-left:auto;color:#6c757d}#toc{max-width:320px}@media (max-width: 768px){#toc{max-width:200px}}</style>");
        out.println("</head><body>");
        out.println("<div id=\"viewer-container\">");
        out.println("  <div id=\"toolbar\">");
        // prev/next
        String prevUrl = self + "&nav=" + Math.max(0, nav - 1);
        String nextUrl = self + "&nav=" + Math.min(count - 1, nav + 1);
        out.println("    <a class=\"btn\" href=\"" + escapeHtml(prevUrl) + "\" " + (nav<=0?"aria-disabled=\"true\" style=\"pointer-events:none;opacity:.5;\"":"") + ">◀ Anterior</a>");
        out.println("    <a class=\"btn\" href=\"" + escapeHtml(nextUrl) + "\" " + (nav>=count-1?"aria-disabled=\"true\" style=\"pointer-events:none;opacity:.5;\"":"") + ">▶ Siguiente</a>");
        // TOC select
        out.println("    <select id=\"toc\" class=\"btn\" onchange=\"if(this.value)location.href=this.value;\">");
        for (int i=0;i<count;i++) {
            String u = self + "&nav=" + i;
            String sel = (i==nav)?" selected":"";
            String label = "Capítulo " + (i+1);
            out.println("      <option value=\"" + escapeHtml(u) + "\"" + sel + ">" + escapeHtml(label) + "</option>");
        }
        out.println("    </select>");
        out.println("    <span id=\"status\">EPUB cargado (" + (nav+1) + "/" + count + ")</span>");
        out.println("  </div>");
        out.println("  <div id=\"iframewrap\"><iframe id=\"pageframe\" src=\"" + escapeHtml(chapterUrl) + "\" title=\"Capítulo\"></iframe></div>");
        out.println("</div>");
        out.println("</body></html>");
    }

    private String url(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
