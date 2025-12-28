package org.sakaiproject.assignment.tool.epub;

import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.component.cover.ComponentManager;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.HttpURLConnection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serves a guaranteed-available demo EPUB.
 *
 * Behaviour:
 * - If assignment.epub.demo.url is configured and reachable, streams that file.
 * - Otherwise generates a minimal valid EPUB on-the-fly (embedded demo).
 */
@Slf4j
public class EpubDemoServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // Always mark as demo
        resp.setHeader("X-EPUB-PROXY", "demo");
        resp.setHeader("Cache-Control", "no-store, no-cache, max-age=0");

        ServerConfigurationService scs = ComponentManager.get(ServerConfigurationService.class);
        String demoUrl = scs != null ? scs.getString("assignment.epub.demo.url") : null;

        // Try remote demo first if configured
        if (demoUrl != null && !demoUrl.trim().isEmpty()) {
            try {
                URL u = new URL(demoUrl);
                HttpURLConnection con = (HttpURLConnection) u.openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(true);
                int code = con.getResponseCode();
                if (code < 400) {
                    String ctype = con.getContentType();
                    resp.setHeader("X-EPUB-MODE", "demo-remote");
                    resp.setHeader("X-EPUB-FINALURL", con.getURL().toString());
                    if (ctype == null || !ctype.toLowerCase().contains("epub")) {
                        // Continue but set proper content type
                        ctype = "application/epub+zip";
                    }
                    resp.setContentType(ctype);
                    String filename = "demo.epub";
                    resp.setHeader("Content-Disposition", "inline; filename=" + filename);
                    try (var in = con.getInputStream(); var out = resp.getOutputStream()) {
                        byte[] buf = new byte[8192];
                        int r;
                        long total = 0L;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                            total += r;
                        }
                        resp.setHeader("X-EPUB-PROXY-DIAG", "ok; remote-bytes=" + total);
                        return;
                    } finally {
                        con.disconnect();
                    }
                } else {
                    resp.setHeader("X-EPUB-PROXY-DIAG", "remote-demo-http-" + code);
                }
            } catch (Exception ex) {
                resp.setHeader("X-EPUB-PROXY-DIAG", "remote-demo-ex: " + ex.getClass().getSimpleName());
                log.warn("[EPUB] Remote demo failed: {}", ex.toString());
            }
        }

        // Fallback: generate embedded demo EPUB on the fly
        resp.setHeader("X-EPUB-MODE", "demo-embedded");
        resp.setContentType("application/epub+zip");
        resp.setHeader("Content-Disposition", "inline; filename=demo.epub");

        try (OutputStream os = resp.getOutputStream(); ZipOutputStream zos = new ZipOutputStream(os)) {
            // Per EPUB spec, mimetype first and uncompressed
            ZipEntry mimetype = new ZipEntry("mimetype");
            mimetype.setMethod(ZipEntry.STORED);
            byte[] mimeBytes = "application/epub+zip".getBytes("US-ASCII");
            mimetype.setSize(mimeBytes.length);
            // Precompute CRC32 for stored entries is required
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(mimeBytes);
            mimetype.setCrc(crc.getValue());
            zos.putNextEntry(mimetype);
            zos.write(mimeBytes);
            zos.closeEntry();

            // META-INF/container.xml
            zos.putNextEntry(new ZipEntry("META-INF/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            String container = "<?xml version=\"1.0\"?>\n" +
                    "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                    "  <rootfiles>\n" +
                    "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                    "  </rootfiles>\n" +
                    "</container>\n";
            zos.write(container.getBytes("UTF-8"));
            zos.closeEntry();

            // OEBPS/title.xhtml
            zos.putNextEntry(new ZipEntry("OEBPS/title.xhtml"));
            String title = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<!DOCTYPE html>\n" +
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                    "<head><title>Demo EPUB</title><meta charset=\"utf-8\"/></head>\n" +
                    "<body><h1>EPUB de prueba</h1><p>Este es un EPUB mínimo generado por el servidor para probar el visor.</p></body>\n" +
                    "</html>\n";
            zos.write(title.getBytes("UTF-8"));
            zos.closeEntry();

            // OEBPS/content.opf
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            String opf = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"BookId\" version=\"2.0\">\n" +
                    "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                    "    <dc:title>Demo EPUB</dc:title>\n" +
                    "    <dc:language>es</dc:language>\n" +
                    "    <dc:identifier id=\"BookId\">urn:uuid:demo-epub</dc:identifier>\n" +
                    "  </metadata>\n" +
                    "  <manifest>\n" +
                    "    <item id=\"title\" href=\"title.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                    "  </manifest>\n" +
                    "  <spine>\n" +
                    "    <itemref idref=\"title\"/>\n" +
                    "  </spine>\n" +
                    "</package>\n";
            zos.write(opf.getBytes("UTF-8"));
            zos.closeEntry();

            zos.finish();
            resp.setHeader("X-EPUB-PROXY-DIAG", "ok; embedded");
        }
    }
}
