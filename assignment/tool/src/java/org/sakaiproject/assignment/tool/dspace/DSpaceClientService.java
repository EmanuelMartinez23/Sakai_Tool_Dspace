package org.sakaiproject.assignment.tool.dspace;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DSpace REST client
 */
@Slf4j
public class DSpaceClientService {

    private final String apiBase; // e.g. http://host:8080/server/api
    private final String frontBase; // e.g. http://host:4000
    private final String email;
    private final String password;

    // simple cache
    private static class CacheEntry {
        Instant ts; Object value;
        CacheEntry(Object v) { this.ts = Instant.now(); this.value = v; }
    }
    private final long ttlMillis;
    private CacheEntry cachedTree;

    public DSpaceClientService(String apiBase, String frontBase, String email, String password, long ttlMillis) {
        this.apiBase = trimSlash(fixScheme(apiBase));
        this.frontBase = trimSlash(fixScheme(frontBase));
        this.email = email;
        this.password = password;
        this.ttlMillis = ttlMillis <= 0 ? 300_000L : ttlMillis; // default 5 min
    }

    private static String trimSlash(String s) {
        if (s == null) return null;
        if (s.endsWith("/")) return s.substring(0, s.length()-1);
        return s;
    }

    /**
     * Corrige bases mal configuradas como "http//host" (falta ":")
     */
    private static String fixScheme(String s) {
        if (s == null) return null;
        if (s.startsWith("http//")) return "http://" + s.substring("http//".length());
        if (s.startsWith("https//")) return "https://" + s.substring("https//".length());
        return s;
    }

    public synchronized List<Map<String, Object>> getDSpaceTree(boolean forceRefresh) {
        log.info("[DSpace] === INICIANDO getDSpaceTree ===");
        log.info("[DSpace] forceRefresh: {}, cachedTree: {}", forceRefresh, cachedTree != null);

        if (!forceRefresh && cachedTree != null && cachedTree.ts.plusMillis(ttlMillis).isAfter(Instant.now())) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> val = (List<Map<String, Object>>) cachedTree.value;
            log.info("[DSpace] ✅ Usando cache existente con {} comunidades", val.size());
            return val;
        }

        try {
            log.info("[DSpace] 🔐 Iniciando autenticación...");
            String token = authenticate();
            if (token == null || token.isEmpty()) {
                log.warn("[DSpace] ❌ No se obtuvo token de autorización");
                return new ArrayList<>();
            }
            log.info("[DSpace] ✅ Autenticación exitosa, token obtenido");

            log.info("[DSpace] 📋 Obteniendo comunidades...");
            Map<String, Object> commResp = getJson(apiBase + "/core/communities?size=1000", token);
            List<Map<String, Object>> communities = extractEmbedded(commResp, "communities");
            log.info("[DSpace] 📊 Comunidades obtenidas: {}", communities != null ? communities.size() : 0);

            List<Map<String, Object>> tree = new ArrayList<>();
            if (communities != null) {
                log.info("[DSpace] 🔄 Procesando {} comunidades", communities.size());

                for (Map<String, Object> c : communities) {
                    String cUuid = str(c.get("uuid"));
                    String cName = str(c.get("name"));
                    log.info("[DSpace]   🏢 Procesando comunidad: {} ({})", cName, cUuid);

                    Map<String, Object> cNode = new HashMap<>();
                    cNode.put("uuid", cUuid);
                    cNode.put("name", cName);
                    cNode.put("url", frontBase + "/communities/" + cUuid);
                    List<Map<String, Object>> collections = new ArrayList<>();

                    // Colecciones por comunidad
                    log.info("[DSpace]     📁 Obteniendo colecciones para comunidad {}", cUuid);
                    Map<String, Object> colResp = getJson(apiBase + "/core/communities/" + cUuid + "/collections?size=1000", token);
                    List<Map<String, Object>> colList = extractEmbedded(colResp, "collections");
                    log.info("[DSpace]     📊 Colecciones encontradas: {}", colList != null ? colList.size() : 0);

                    if (colList != null) {
                        for (Map<String, Object> col : colList) {
                            String colUuid = str(col.get("uuid"));
                            String colName = str(col.get("name"));
                            log.info("[DSpace]       📂 Procesando colección: {} ({})", colName, colUuid);

                            Map<String, Object> colNode = new HashMap<>();
                            colNode.put("uuid", colUuid);
                            colNode.put("name", colName);
                            colNode.put("url", frontBase + "/collections/" + colUuid);
                            List<Map<String, Object>> items = new ArrayList<>();

                            log.info("[DSpace]         📄 Obteniendo todos los items...");
                            Map<String, Object> itemsResp = getJson(apiBase + "/core/items?size=1000", token);
                            List<Map<String, Object>> allItems = extractEmbedded(itemsResp, "items");
                            log.info("[DSpace]         📊 Total de items encontrados: {}", allItems != null ? allItems.size() : 0);

                            if (allItems != null) {
                                int itemsInCollection = 0;
                                for (Map<String, Object> item : allItems) {
                                    // Identficadores unicos  de colecciones
                                    String owningHref = linkHref(item, "owningCollection");
                                    String owningUuid = null;
                                    if (owningHref != null) {
                                        log.debug("[DSpace]           🔍 Verificando owningCollection: {}", owningHref);
                                        Map<String, Object> owningResp = getJson(owningHref, token);
                                        owningUuid = str(owningResp == null ? null : owningResp.get("uuid"));
                                    }
                                    if (owningUuid == null || !owningUuid.equals(colUuid)) {
                                        continue;
                                    }


                                    String itemUuid = str(item.get("uuid"));
                                    String title = extractTitle(item);
                                    log.info("[DSpace]           📝 Procesando item: {} ({})", title, itemUuid);

                                    Map<String, Object> itemNode = new HashMap<>();
                                    itemNode.put("uuid", itemUuid);
                                    itemNode.put("title", title);
                                    itemNode.put("url", frontBase + "/items/" + itemUuid);
                                    List<Map<String, Object>> bitstreams = new ArrayList<>();

                                    // bundles
                                    String bundlesHref = linkHref(item, "bundles");
                                    if (bundlesHref != null) {
                                        log.debug("[DSpace]             📦 Obteniendo bundles para item {}", itemUuid);
                                        Map<String, Object> bundlesResp = getJson(bundlesHref + (bundlesHref.contains("?")?"&":"?") + "size=1000", token);
                                        List<Map<String, Object>> bundles = extractEmbedded(bundlesResp, "bundles");
                                        log.debug("[DSpace]             📊 Bundles encontrados: {}", bundles != null ? bundles.size() : 0);

                                        if (bundles != null) {
                                            for (Map<String, Object> b : bundles) {
                                                String bundleName = str(b.get("name"));
                                                if (bundleName == null || !"ORIGINAL".equalsIgnoreCase(bundleName)) {
                                                    log.debug("[DSpace]               ⏩ Saltando bundle: {}", bundleName);
                                                    continue;
                                                }
                                                log.debug("[DSpace]               📎 Procesando bundle ORIGINAL");

                                                String bitsHref = linkHref(b, "bitstreams");
                                                if (bitsHref != null) {
                                                    log.debug("[DSpace]                 📎 Obteniendo bitstreams...");
                                                    Map<String, Object> bitsResp = getJson(bitsHref + (bitsHref.contains("?")?"&":"?") + "size=1000", token);
                                                    List<Map<String, Object>> bits = extractEmbedded(bitsResp, "bitstreams");
                                                    log.debug("[DSpace]                 📊 Bitstreams encontrados: {}", bits != null ? bits.size() : 0);

                                                    if (bits != null) {
                                                        for (Map<String, Object> bs : bits) {
                                                            Map<String, Object> bn = new HashMap<>();
                                                            String bsUuid = str(bs.get("uuid"));
                                                            String bsName = str(bs.get("name"));
                                                            log.info("[DSpace]                   📄 Bitstream: {} ({})", bsName, bsUuid);

                                                            bn.put("uuid", bsUuid);
                                                            bn.put("name", bsName);
                                                            bn.put("sizeBytes", bs.get("sizeBytes"));
                                                            bn.put("mimeType", str(bs.get("mimeType")));
                                                            bn.put("bundleName", bundleName);
                                                            bn.put("downloadUrl", frontBase + "/bitstreams/" + bsUuid + "/download");
                                                            bitstreams.add(bn);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    itemNode.put("bitstreams", bitstreams);
                                    items.add(itemNode);
                                    itemsInCollection++;
                                    log.info("[DSpace]           ✅ Item procesado: {} ({} bitstreams)", title, bitstreams.size());
                                }
                                log.info("[DSpace]         ✅ Items en colección {}: {}", colName, itemsInCollection);
                            }

                            colNode.put("items", items);
                            collections.add(colNode);
                            log.info("[DSpace]       ✅ Colección procesada: {} ({} items)", colName, items.size());
                        }
                    }

                    cNode.put("collections", collections);
                    tree.add(cNode);
                    log.info("[DSpace]     ✅ Comunidad procesada: {} ({} colecciones)", cName, collections.size());
                }
            }

            this.cachedTree = new CacheEntry(tree);
            log.info("[DSpace] ✅ Árbol DSpace construido exitosamente: {} comunidades, cache actualizado", tree.size());
            return tree;

        } catch (Exception e) {
            log.error("[DSpace] ❌ Error crítico construyendo árbol DSpace", e);
            log.warn("[DSpace] ⚠️  Error building tree: {}", e.toString());
            return new ArrayList<>();
        } finally {
            log.info("[DSpace] === FINALIZANDO getDSpaceTree ===");
        }
    }

    private String authenticate() throws IOException {
        log.info("[DSpace] 🔐 INICIANDO PROCESO DE AUTENTICACIÓN");
        log.info("[DSpace] 📍 URL base: {}", apiBase);
        log.info("[DSpace] 👤 Usuario: {}", email);

        // 1) GET /security/csrf obtener token CSRF
        log.info("[DSpace] 📥 Paso 1: Obteniendo token CSRF desde: {}/security/csrf", apiBase);
        HttpURLConnection con1 = (HttpURLConnection) new URL(apiBase + "/security/csrf").openConnection();
        con1.setRequestMethod("GET");
        con1.setInstanceFollowRedirects(false);
        con1.setDoInput(true);

        try {
            int code1 = con1.getResponseCode();
            log.info("[DSpace] 📋 Código de respuesta CSRF: {}", code1);

            String setCookie = con1.getHeaderField("Set-Cookie");
            log.info("[DSpace] 🍪 Header Set-Cookie recibido: {}", setCookie);

            log.debug("[DSpace] 📨 Todas las cabeceras de respuesta CSRF:");
            Map<String, List<String>> headers = con1.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                log.debug("[DSpace]   {}: {}", entry.getKey(), entry.getValue());
            }

            String xsrfCookie = extractXsrfCookie(setCookie);
            log.info("[DSpace] 🔑 Token XSRF extraído: {}", xsrfCookie != null ? xsrfCookie.substring(0, Math.min(10, xsrfCookie.length())) + "..." : "null");

            safeClose(con1);

            if (xsrfCookie == null) {
                log.error("[DSpace] ❌ No se recibió cookie XSRF, código HTTP: {}", code1);
                log.error("[DSpace] 💡 Posibles causas: URL incorrecta, servidor no responde, problemas de red");
                return null;
            }

            log.info("[DSpace] ✅ Token CSRF obtenido exitosamente");

            // 2) POST /authn/login login con headers
            log.info("[DSpace] 📤 Paso 2: Enviando credenciales de login a: {}/authn/login", apiBase);
            HttpURLConnection con2 = (HttpURLConnection) new URL(apiBase + "/authn/login").openConnection();
            con2.setRequestMethod("POST");
            con2.setInstanceFollowRedirects(false);
            con2.setDoOutput(true);
            // Configurar headers
            con2.setRequestProperty("X-XSRF-TOKEN", xsrfCookie);
            con2.setRequestProperty("Cookie", "DSPACE-XSRF-COOKIE=" + xsrfCookie);
            con2.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            log.info("[DSpace] 📝 Headers configurados:");
            log.info("[DSpace]   X-XSRF-TOKEN: {}...", xsrfCookie.substring(0, Math.min(10, xsrfCookie.length())));
            log.info("[DSpace]   Cookie: DSPACE-XSRF-COOKIE={}...", xsrfCookie.substring(0, Math.min(10, xsrfCookie.length())));
            log.info("[DSpace]   Content-Type: application/x-www-form-urlencoded");

            String body = "user=" + urlEncode(email) + "&password=" + urlEncode(password);
            log.info("[DSpace] 👤 Credenciales - Usuario: {}, Password length: {}", email, password != null ? password.length() : 0);
            log.debug("[DSpace] 📦 Body de login (password enmascarado): user={}&password=***", urlEncode(email));

            try (OutputStream os = con2.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                log.info("[DSpace] ✅ Body de login enviado exitosamente");
            } catch (IOException e) {
                log.error("[DSpace] ❌ Error enviando body de login: {}", e.getMessage());
                safeClose(con2);
                return null;
            }

            int loginResponseCode = con2.getResponseCode();
            log.info("[DSpace] 📋 Código de respuesta login: {}", loginResponseCode);

            log.info("[DSpace] 📨 Cabeceras de respuesta de login:");
            Map<String, List<String>> loginHeaders = con2.getHeaderFields();
            for (Map.Entry<String, List<String>> entry : loginHeaders.entrySet()) {
                if (entry.getKey() != null) {
                    log.info("[DSpace]   {}: {}", entry.getKey(), entry.getValue());
                } else {
                    log.info("[DSpace]   Status: {}", entry.getValue());
                }
            }

            String authHeader = con2.getHeaderField("Authorization");
            log.info("[DSpace] 🎫 Header Authorization recibido: {}", authHeader);

            safeClose(con2);

            if (authHeader == null) {
                log.error("[DSpace] ❌ Header Authorization no encontrado en la respuesta");
                log.error("[DSpace] 💡 Posibles causas:");
                log.error("[DSpace]   - Credenciales incorrectas");
                log.error("[DSpace]   - Servidor DSpace no configurado correctamente");
                log.error("[DSpace]   - Problemas de CORS/seguridad");
                log.error("[DSpace]   - Ruta de autenticación incorrecta");
                return null;
            }

            if (!authHeader.toLowerCase().startsWith("bearer ")) {
                log.error("[DSpace] ❌ Header Authorization no contiene Bearer token");
                log.error("[DSpace]   Valor recibido: {}", authHeader);
                log.error("[DSpace]   Esperado: 'Bearer <token>'");
                return null;
            }

            String token = authHeader.substring(7).trim();
            log.info("[DSpace] ✅ AUTENTICACIÓN EXITOSA - Token obtenido (primeros 10 chars): {}...",
                    token.substring(0, Math.min(10, token.length())));
            log.info("[DSpace] 🎉 PROCESO DE AUTENTICACIÓN COMPLETADO");

            return token;

        } catch (IOException e) {
            log.error("[DSpace] 💥 ERROR de conexión durante autenticación: {}", e.getMessage());
            log.error("[DSpace] 🔍 Stack trace completo:", e);
            safeClose(con1);
            throw e;
        }
    }

    private Map<String, Object> getJson(String url, String bearer) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Accept", "application/json");
        if (bearer != null) con.setRequestProperty("Authorization", "Bearer " + bearer);
        int code = con.getResponseCode();
        if (code >= 200 && code < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return Json.parseToMap(sb.toString());
            } finally {
                safeClose(con);
            }
        } else {
            log.warn("[DSpace] GET {} -> HTTP {}", url, code);
            safeClose(con);
            return null;
        }
    }

    private static String extractXsrfCookie(String setCookie) {
        if (setCookie == null) return null;
        // Look for DSPACE-XSRF-COOKIE=...
        String tokenName = "DSPACE-XSRF-COOKIE=";
        int idx = setCookie.indexOf(tokenName);
        if (idx < 0) return null;
        int start = idx + tokenName.length();
        int end = setCookie.indexOf(';', start);
        if (end < 0) end = setCookie.length();
        return setCookie.substring(start, end);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractEmbedded(Map<String, Object> resp, String key) {
        if (resp == null) return null;
        Object embedded = resp.get("_embedded");
        if (!(embedded instanceof Map)) return null;
        Object list = ((Map<String, Object>) embedded).get(key);
        if (list instanceof List) return (List<Map<String, Object>>) list;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String linkHref(Map<String, Object> obj, String rel) {
        if (obj == null) return null;
        Object links = obj.get("_links");
        if (!(links instanceof Map)) return null;
        Object relObj = ((Map<String, Object>) links).get(rel);
        if (!(relObj instanceof Map)) return null;
        Object href = ((Map<String, Object>) relObj).get("href");
        return href == null ? null : href.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractTitle(Map<String, Object> item) {
        if (item == null) return null;
        Object md = item.get("metadata");
        if (!(md instanceof Map)) return null;
        Object titleArr = ((Map<String, Object>) md).get("dc.title");
        if (titleArr instanceof List && !((List<?>) titleArr).isEmpty()) {
            Object first = ((List<?>) titleArr).get(0);
            if (first instanceof Map) {
                Object v = ((Map<String, Object>) first).get("value");
                return v == null ? null : v.toString();
            }
        }
        return null;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static void safeClose(HttpURLConnection con) {
        if (con != null) try { con.disconnect(); } catch (Exception ignore) {}
    }

    /** Minimal JSON parser to Map using org.json built into Java is not available; implement a thin adapter using Jackson if present.
     * Here we provide a tiny wrapper that relies on the presence of com.fasterxml.jackson in Sakai. */
    static class Json {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        static Map<String, Object> parseToMap(String json) throws IOException {
            if (json == null || json.isEmpty()) return new HashMap<>();
            return MAPPER.readValue(json, Map.class);
        }
    }
}
