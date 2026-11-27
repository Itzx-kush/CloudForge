package com.cloudforge.backend.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderSpaRoutingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void publicSpaRoutesForwardToReactWithoutNeedingAuthentication() throws Exception {
        for (String route : new String[] {
                "/", "/login", "/projects", "/projects/42", "/repositories",
                "/deployments", "/pipelines", "/monitoring", "/settings"
        }) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", containsString("no-cache")))
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void apiEndpointsRemainAuthenticatedEvenThoughSpaShellIsPublic() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/workspaces"))
                .andExpect(status().isUnauthorized());
    }
}
