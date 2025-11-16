package org.sakaiproject.contacts.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.sakaiproject.contacts.api.Contact;
import org.sakaiproject.contacts.api.ContactService;
import org.springframework.stereotype.Service;

@Service("org.sakaiproject.contacts.api.ContactService")
public class ContactServiceImpl implements ContactService {

    private final Map<String, Map<String, Contact>> store = new ConcurrentHashMap<>();

    @Override
    public List<Contact> listForSite(String siteId) {
        Map<String, Contact> site = store.get(siteId);
        if (site == null) return Collections.emptyList();
        return new ArrayList<>(site.values());
    }

    @Override
    public Contact get(String siteId, String id) {
        Map<String, Contact> site = store.get(siteId);
        if (site == null) return null;
        return site.get(id);
    }

    @Override
    public Contact create(Contact contact) {
        if (contact.getId() == null || contact.getId().isEmpty()) {
            contact.setId(UUID.randomUUID().toString());
        }
        contact.setCreatedAt(Instant.now());
        contact.setModifiedAt(contact.getCreatedAt());
        store.computeIfAbsent(contact.getSiteId(), k -> new ConcurrentHashMap<>())
             .put(contact.getId(), contact);
        return contact;
    }

    @Override
    public Contact update(Contact contact) {
        contact.setModifiedAt(Instant.now());
        store.computeIfAbsent(contact.getSiteId(), k -> new ConcurrentHashMap<>())
             .put(contact.getId(), contact);
        return contact;
    }

    @Override
    public void delete(String siteId, String id) {
        Map<String, Contact> site = store.get(siteId);
        if (site != null) {
            site.remove(id);
        }
    }
}
