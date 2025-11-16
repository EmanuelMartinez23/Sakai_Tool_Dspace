/*
 * Contacts Tool Controller
 */
package org.sakaiproject.contacts.tool;

import java.util.List;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.contacts.api.Contact;
import org.sakaiproject.contacts.api.ContactService;
import org.sakaiproject.portal.util.PortalUtils;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.tool.api.Placement;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.authz.api.SecurityService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class ContactsController {

    private static final Logger logger = LoggerFactory.getLogger(ContactsController.class);

    @Resource private SessionManager sessionManager;
    @Resource private ToolManager toolManager;
    @Resource(name = "org.sakaiproject.contacts.api.ContactService")
    private ContactService contactService;
    @Resource private SecurityService securityService;
    @Resource private SiteService siteService;

    @GetMapping({"/", "/index"})
    public String index(Model model, HttpServletRequest request) {
        checkSakaiSession();
        loadModel(model, request);

        String siteId = (String) model.asMap().get("siteId");
        List<Contact> contacts = contactService.listForSite(siteId);
        model.addAttribute("contacts", contacts);
        model.addAttribute("newContact", new Contact());
        model.addAttribute("canEdit", canMaintain(siteId));
        return "index";
    }

    @PostMapping("/create")
    public String create(@ModelAttribute("newContact") Contact newContact, Model model, HttpServletRequest request) {
        checkSakaiSession();
        loadModel(model, request);
        String siteId = (String) model.asMap().get("siteId");

        if (!canMaintain(siteId)) {
            // silently ignore or show an error message
            model.addAttribute("error", "No tienes permisos para crear contactos.");
            return "index";
        }

        newContact.setSiteId(siteId);
        contactService.create(newContact);
        return "redirect:/";
    }

    private boolean canMaintain(String siteId) {
        try {
            return securityService.unlock(SiteService.SECURE_UPDATE_SITE, siteService.siteReference(siteId));
        } catch (Exception e) {
            log.warn("Permission check failed for site {}", siteId, e);
            return false;
        }
    }

    private void loadModel(Model model, HttpServletRequest request) {
        model.addAttribute("cdnQuery", PortalUtils.getCDNQuery());
        Placement placement = toolManager.getCurrentPlacement();
        model.addAttribute("siteId", placement.getContext());
        String baseUrl = "/portal/site/" + placement.getContext() + "/tool/" + toolManager.getCurrentPlacement().getId();
        model.addAttribute("baseUrl", baseUrl);
        model.addAttribute("sakaiHtmlHead", (String) request.getAttribute("sakai.html.head"));
    }

    private Session checkSakaiSession() {
        try {
            Session session = sessionManager.getCurrentSession();
            if (StringUtils.isBlank(session.getUserId())) {
                log.error("Sakai user session is invalid");
                throw new IllegalStateException("Missing Sakai session");
            }
            return session;
        } catch (IllegalStateException e) {
            throw e;
        }
    }
}
