package org.sakaiproject.contacts.tool;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.contacts.api.Contact;
import org.sakaiproject.contacts.api.ContactService;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Placement;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.vm.VmServlet;

/**
 * ContactsAction — minimal servlet using Sakai Velocity/CHEF stack.
 * Renders a simple list of contacts for the current site.
 */
public class ContactsAction extends VmServlet {

    private static final long serialVersionUID = 1L;

    private transient SessionManager sessionManager;
    private transient ToolManager toolManager;
    private transient SecurityService securityService;
    private transient SiteService siteService;
    private transient ContactService contactService;

    private String templatePath; // e.g. "contacts/chef_contacts"

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        // Resolve init-param template
        this.templatePath = config.getInitParameter("template");
        if (StringUtils.isBlank(this.templatePath)) {
            this.templatePath = "contacts/chef_contacts";
        }
        // Wire Sakai services via ComponentManager
        this.sessionManager = ComponentManager.get(SessionManager.class);
        this.toolManager = ComponentManager.get(ToolManager.class);
        this.securityService = ComponentManager.get(SecurityService.class);
        this.siteService = ComponentManager.get(SiteService.class);
        Object svc = ComponentManager.get("org.sakaiproject.contacts.api.ContactService");
        if (svc instanceof ContactService) {
            this.contactService = (ContactService) svc;
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ensureSakaiSession();
        loadModel(request);
        includeVm(templatePath, request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ensureSakaiSession();
        String action = StringUtils.defaultIfBlank(request.getParameter("action"), "");
        if ("create".equals(action)) {
            handleCreate(request);
        }
        // Reload the model and render
        loadModel(request);
        includeVm(templatePath, request, response);
    }

    private void loadModel(HttpServletRequest request) {
        Placement placement = toolManager.getCurrentPlacement();
        String siteId = placement != null ? placement.getContext() : null;
        request.setAttribute("siteId", siteId);

        if (contactService != null && StringUtils.isNotBlank(siteId)) {
            List<Contact> contacts = contactService.listForSite(siteId);
            request.setAttribute("contacts", contacts);
        }

        boolean canEdit = false;
        try {
            canEdit = securityService != null && siteService != null && StringUtils.isNotBlank(siteId)
                && securityService.unlock(SiteService.SECURE_UPDATE_SITE, siteService.siteReference(siteId));
        } catch (Exception e) {
            canEdit = false;
        }
        request.setAttribute("canEdit", canEdit);
    }

    private void handleCreate(HttpServletRequest request) {
        if (contactService == null) return;
        Placement placement = toolManager.getCurrentPlacement();
        String siteId = placement != null ? placement.getContext() : null;
        if (StringUtils.isBlank(siteId)) return;

        boolean canEdit = false;
        try {
            canEdit = securityService != null && siteService != null
                && securityService.unlock(SiteService.SECURE_UPDATE_SITE, siteService.siteReference(siteId));
        } catch (Exception e) {
            canEdit = false;
        }
        if (!canEdit) return;

        // Build a minimal Contact from form params
        Contact c = new Contact();
        c.setSiteId(siteId);
        c.setName(StringUtils.trimToNull(request.getParameter("name")));
        c.setEmail(StringUtils.trimToNull(request.getParameter("email")));
        c.setPhone(StringUtils.trimToNull(request.getParameter("phone")));
        c.setRole(StringUtils.trimToNull(request.getParameter("role")));
        c.setTags(StringUtils.trimToNull(request.getParameter("tags")));

        if (StringUtils.isNotBlank(c.getName()) && StringUtils.isNotBlank(c.getEmail())) {
            contactService.create(c);
        }
    }

    private Session ensureSakaiSession() throws ServletException {
        if (sessionManager == null) {
            throw new ServletException("Sakai SessionManager not available");
        }
        Session s = sessionManager.getCurrentSession();
        if (s == null || StringUtils.isBlank(s.getUserId())) {
            throw new ServletException("Sakai user session is invalid");
        }
        return s;
    }
}
