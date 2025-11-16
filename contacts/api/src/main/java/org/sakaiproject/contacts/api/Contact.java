package org.sakaiproject.contacts.api;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

public class Contact implements Serializable {

    private String id; // unique within site
    private String siteId;

    private String fullName;
    private String email;
    private String phone;

    private String role; // free text or site role label
    private List<String> tags;

    private String notes;

    private String createdBy;
    private Instant createdAt;
    private Instant modifiedAt;

    public Contact() {}

    public Contact(String id, String siteId) {
        this.id = id;
        this.siteId = siteId;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSiteId() { return siteId; }
    public void setSiteId(String siteId) { this.siteId = siteId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(Instant modifiedAt) { this.modifiedAt = modifiedAt; }
}
