package org.sakaiproject.notes.tool;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.tool.api.Placement;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.api.ToolManager;
import org.sakaiproject.vm.VmServlet;

/**
 * NotesAction — Minimal Sakai Velocity/CHEF servlet that renders a Hello World.
 */
public class NotesAction extends VmServlet {

    private static final long serialVersionUID = 1L;

    private transient SessionManager sessionManager;
    private transient ToolManager toolManager;
    private String templatePath; // e.g. "notes/chef_notes"

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        this.templatePath = config.getInitParameter("template");
        if (StringUtils.isBlank(this.templatePath)) {
            this.templatePath = "notes/chef_notes";
        }
        this.sessionManager = ComponentManager.get(SessionManager.class);
        this.toolManager = ComponentManager.get(ToolManager.class);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ensureSakaiSession();
        // Basic model
        Placement placement = toolManager != null ? toolManager.getCurrentPlacement() : null;
        if (placement != null) {
            request.setAttribute("siteId", placement.getContext());
        }
        request.setAttribute("title", "Notas");
        request.setAttribute("message", "Hello World");
        includeVm(templatePath, request, response);
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
