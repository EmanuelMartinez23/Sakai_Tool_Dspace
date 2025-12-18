package org.sakaiproject.assignment.tool.epub;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.content.api.ContentCollection;
import org.sakaiproject.content.api.ContentCollectionEdit;
import org.sakaiproject.content.api.ContentHostingService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.content.api.ContentResourceEdit;
import org.sakaiproject.entity.api.ResourcePropertiesEdit;
import org.sakaiproject.tool.api.SessionManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes a per-user extracted EPUB into ContentHostingService so chapters/resources can be served via /access/content.
 * We use the user collection: /user/{userId}/epub/{uuid}/...
 */
@Slf4j
public class EpubChsStorage {

    private final ContentHostingService chs;
    private final SessionManager sessionManager;

    public EpubChsStorage() {
        this.chs = ComponentManager.get(ContentHostingService.class);
        this.sessionManager = ComponentManager.get(SessionManager.class);
    }

    @Data
    public static class Published {
        private String userId;
        private String uuid;
        private String collectionId; // e.g. /user/123/epub/332d.../
        private List<String> spine;  // relative paths inside the collection
        private String title;
    }

    public String getUserRoot(String userId) {
        // Standard CHS path for user collections
        if (!userId.startsWith("/")) {
            return "/user/" + userId + "/epub"; // collection
        }
        // never happens for userId, but keep a safe default
        return "/user/" + userId.replace("/", "_") + "/epub";
    }

    public Published ensurePublished(String userId, String uuid) throws IOException {
        if (chs == null) throw new IOException("ContentHostingService unavailable");
        EpubStorageUtil util = new EpubStorageUtil();
        EpubStorageUtil.Index idx = util.ensureExtracted(userId, uuid);

        String rootId = getUserRoot(userId);
        String bookCollectionId = rootId + "/" + safe(uuid) + "/"; // CHS collections must end with '/'

        // Ensure the collections exist
        ensureCollection(rootId, "User EPUB root");
        ensureCollection(bookCollectionId, "EPUB " + uuid);

        // Publish files recursively under bookCollectionId
        File bookDir = util.getBookDir(userId, uuid);
        publishDirectory(bookDir, bookCollectionId, bookDir);

        Published p = new Published();
        p.setUserId(userId);
        p.setUuid(uuid);
        p.setCollectionId(bookCollectionId);
        p.setTitle((idx.title != null && !idx.title.isEmpty()) ? idx.title : "EPUB");
        // spine paths are relative to the bookDir; reuse them as-is under CHS (same relative layout)
        p.setSpine(new ArrayList<>(idx.spine));
        return p;
    }

    private void ensureCollection(String collectionId, String title) throws IOException {
        try {
            ContentCollection existing = chs.getCollection(collectionId);
            if (existing != null) return;
        } catch (Exception notExists) {
            // create it
            try {
                // ensure parent exists first
                int last = collectionId.lastIndexOf('/', collectionId.length() - 2);
                if (last > 0) {
                    String parent = collectionId.substring(0, last + 1);
                    try { chs.getCollection(parent); } catch (Exception e) { ensureCollection(parent, "EPUB Root"); }
                }
                ContentCollectionEdit edit = chs.addCollection(collectionId);
                ResourcePropertiesEdit props = edit.getPropertiesEdit();
                props.addProperty(ResourcePropertiesEdit.PROP_DISPLAY_NAME, title);
                chs.commitCollection(edit);
            } catch (Exception e) {
                log.warn("[EPUB][CHS] create collection failed {}: {}", collectionId, e.toString());
                throw new IOException("No se pudo crear la colección CHS: " + collectionId);
            }
        }
    }

    private void publishDirectory(File srcDir, String chsBaseCollection, File bookRoot) throws IOException {
        File[] list = srcDir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                String rel = bookRoot.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                String collId = chsBaseCollection + normalizeRel(rel) + "/";
                ensureCollection(collId, f.getName());
                publishDirectory(f, chsBaseCollection, bookRoot);
            } else {
                publishFile(f, chsBaseCollection, bookRoot);
            }
        }
    }

    private void publishFile(File src, String chsBaseCollection, File bookRoot) throws IOException {
        String rel = bookRoot.toPath().relativize(src.toPath()).toString().replace('\\', '/');
        String resId = chsBaseCollection + normalizeRel(rel);
        // If exists and sizes match, skip
        try {
            ContentResource existing = chs.getResource(resId);
            if (existing != null && existing.getContentLength() == Files.size(src.toPath())) {
                return;
            }
        } catch (Exception ignore) { /* not exists */ }

        // Ensure parent collection exists
        int lastSlash = resId.lastIndexOf('/');
        if (lastSlash > 0) {
            String coll = resId.substring(0, lastSlash + 1);
            ensureCollection(coll, "EPUB");
        }
        try (InputStream in = new FileInputStream(src)) {
            ContentResourceEdit edit;
            try {
                edit = chs.addResource(resId);
            } catch (Exception exists) {
                // Try edit existing
                try {
                    chs.removeResource(resId);
                    edit = chs.addResource(resId);
                } catch (Exception e) {
                    log.warn("[EPUB][CHS] overwrite resource failed {}: {}", resId, e.toString());
                    throw new IOException("No se pudo publicar recurso CHS: " + resId);
                }
            }
            edit.setContent(in);
            edit.setContentType(EpubStorageUtil.contentType(src.getName()));
            ResourcePropertiesEdit props = edit.getPropertiesEdit();
            props.addProperty(ResourcePropertiesEdit.PROP_DISPLAY_NAME, src.getName());
            chs.commitResource(edit, 0);
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            log.warn("[EPUB][CHS] add resource failed {}: {}", resId, e.toString());
            throw new IOException("Error publicando en CHS: " + resId);
        }
    }

    private String normalizeRel(String rel) {
        String r = rel.replace('\\', '/');
        while (r.startsWith("/")) r = r.substring(1);
        return r;
    }

    private String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
