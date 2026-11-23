package com.cloudforge.backend.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serve the built React SPA from the same HTTPS origin as Spring Security.
 * Keep API, OAuth, and GitHub App callback routes under their existing handlers.
 */
@Controller
public class SpaPageController {

    @GetMapping({
            "/", "/login", "/projects", "/projects/{projectId}",
            "/repositories", "/deployments", "/pipelines", "/monitoring", "/settings"
    })
    public String index() {
        return "forward:/index.html";
    }
}
