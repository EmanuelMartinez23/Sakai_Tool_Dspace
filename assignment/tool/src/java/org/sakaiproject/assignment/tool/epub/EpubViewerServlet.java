package org.sakaiproject.assignment.tool.epub;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Serves an EPUB viewer HTML from a servlet path so it works inside the Sakai portal tool context.
 * The viewer expects a parameter named "src" either in the query string or in the URL fragment (#src=...).
 * It will always route the EPUB load through the local proxy endpoint to avoid CORS and to support large files.
 */
public class EpubViewerServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html;charset=UTF-8");
        final String contextPath = req.getContextPath();

        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html>");
        out.println("<html lang=\"es\">");
        out.println("<head>");
        out.println("  <meta charset=\"utf-8\" />");
        out.println("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />");
        out.println("  <title>Visor EPUB</title>");
        out.println("  <style>html,body{height:100%;margin:0}body{display:flex;flex-direction:column;font-family:system-ui,-apple-system,Segoe UI,Roboto,Ubuntu,Cantarell,'Helvetica Neue',Arial,'Noto Sans',sans-serif}.toolbar{display:flex;align-items:center;gap:.5rem;padding:.5rem .75rem;border-bottom:1px solid #e5e5e5;background:#fafafa}.toolbar .title{font-weight:600;margin-right:auto;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.toolbar button{padding:.35rem .65rem;font-size:.9rem;cursor:pointer}.toolbar select{max-width:40vw}.viewer{position:relative;flex:1;min-height:0;background:#f3f3f3}#viewer{position:absolute;inset:0;margin:auto;width:min(900px,100%);height:100%;box-shadow:0 0 0 1px #ddd inset;background:#fff}.footer{display:flex;align-items:center;gap:.75rem;padding:.4rem .75rem;border-top:1px solid #e5e5e5;background:#fafafa;font-size:.85rem}.spacer{flex:1}</style>");
        out.println("  <script src=\"https://cdn.jsdelivr.net/npm/epubjs@0.3/dist/epub.min.js\"></script>");
        out.println("</head>");
        out.println("<body>");
        out.println("  <div class=\"toolbar\">\n    <div class=\"title\" id=\"bookTitle\">EPUB</div>\n    <button id=\"prevBtn\" title=\"Anterior\">⟨</button>\n    <button id=\"nextBtn\" title=\"Siguiente\">⟩</button>\n    <select id=\"tocSelect\" title=\"Tabla de contenidos\">\n      <option value=\"\">Índice…</option>\n    </select>\n  </div>");
        out.println("  <div class=\"viewer\">\n    <div id=\"viewer\" aria-label=\"EPUB viewer\" role=\"region\"></div>\n  </div>");
        out.println("  <div class=\"footer\">\n    <span id=\"locInfo\">Página 1</span>\n    <span class=\"spacer\"></span>\n    <a id=\"openSrc\" href=\"#\" target=\"_blank\" rel=\"noopener\" title=\"Abrir archivo original\">Abrir archivo</a>\n  </div>");
        out.println("  <script>\n    (function(){\n      var CONTEXT_PATH = " + toJsString(contextPath) + ";\n      function getParam(name){\n        var mSearch = new RegExp('(^|[?&])' + name + '=([^&]*)').exec(location.search);\n        if (mSearch) return decodeURIComponent(mSearch[2]);\n        var mHash = new RegExp('(^|[#&])' + name + '=([^&]*)').exec(location.hash);\n        return mHash ? decodeURIComponent(mHash[2]) : '';\n      }\n      var original = getParam('src');\n      if (!original) {\n        document.getElementById('bookTitle').textContent = 'Sin fuente EPUB';\n        document.getElementById('locInfo').textContent = 'No se proporcionó URL de EPUB.';\n        document.getElementById('openSrc').remove();\n        return;\n      }\n      // Siempre usar el proxy local para evitar CORS/falta de rangos\n      var proxied = CONTEXT_PATH + '/epub/proxy?src=' + encodeURIComponent(original);\n      document.getElementById('openSrc').href = original;\n\n      try {\n        var book = ePub(proxied);\n        var rendition = book.renderTo('viewer', { width:'100%', height:'100%', spread:'always', flow:'paginated' });\n        var KEY = 'epub-viewer-cfi:' + original;\n        var saved = sessionStorage.getItem(KEY);\n        rendition.display(saved || undefined);\n        document.getElementById('prevBtn').addEventListener('click', function(){ rendition.prev();});\n        document.getElementById('nextBtn').addEventListener('click', function(){ rendition.next();});\n        rendition.on('relocated', function(loc){\n          try {\n            sessionStorage.setItem(KEY, loc && loc.start && loc.start.cfi ? loc.start.cfi : '');\n            var percent = 0;\n            if (book && book.locations && book.locations.percentageFromCfi && loc && loc.start) {\n              try { percent = Math.round(book.locations.percentageFromCfi(loc.start.cfi) * 100); } catch(e) {}\n            }\n            document.getElementById('locInfo').textContent = percent ? ('Progreso: ' + percent + '%') : '';\n          } catch(e) {}\n        });\n        book.loaded.metadata.then(function(meta){ if (meta && meta.title) document.getElementById('bookTitle').textContent = meta.title; }).catch(function(){});\n        book.ready.then(function(){ return book.loaded.navigation; }).then(function(nav){\n          var select = document.getElementById('tocSelect');\n          function addOption(item, depth){\n            var opt = document.createElement('option');\n            opt.value = item.href;\n            var prefix = new Array((depth||0)+1).join('—');\n            opt.textContent = (prefix ? prefix + ' ' : '') + (item.label || item.title || 'Sección');\n            select.appendChild(opt);\n            if (item.subitems) item.subitems.forEach(function(sub){ addOption(sub, (depth||0)+1); });\n          }\n          if (nav && nav.toc) nav.toc.forEach(function(i){ addOption(i, 0); });\n          select.addEventListener('change', function(){ if (this.value) rendition.display(this.value); });\n        }).catch(function(){});\n        window.addEventListener('resize', function(){ rendition && rendition.resize('100%', '100%'); });\n      } catch (e) {\n        try { console.error(e); } catch(ignored) {}\n        document.getElementById('locInfo').textContent = 'No se pudo cargar el EPUB.';\n      }\n    })();\n    function toAbs(p){ return p; }\n  </script>");
        out.println("</body>");
        out.println("</html>");
    }

    private String toJsString(String s){
        if (s == null) return "\"\"";
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }
}
