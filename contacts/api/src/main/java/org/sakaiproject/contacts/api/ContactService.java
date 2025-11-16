package org.sakaiproject.contacts.api;

import java.util.List;

public interface ContactService {

    List<Contact> listForSite(String siteId);

    Contact get(String siteId, String id);

    Contact create(Contact contact);

    Contact update(Contact contact);

    void delete(String siteId, String id);
}
