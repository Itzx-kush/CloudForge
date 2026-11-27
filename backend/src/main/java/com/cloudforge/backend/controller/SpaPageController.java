package com.cloudforge.backend.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
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
    public String index(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        return "forward:/index.html";
    }
}
