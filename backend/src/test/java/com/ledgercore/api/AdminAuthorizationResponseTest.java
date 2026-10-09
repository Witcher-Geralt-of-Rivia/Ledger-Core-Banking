package com.ledgercore.api;

import com.ledgercore.audit.AuditService;
import com.ledgercore.auth.AuthPrincipal;
import com.ledgercore.auth.JwtService;
import com.ledgercore.authz.AuthzService;
import com.ledgercore.common.error.DomainException;
import com.ledgercore.security.SecurityConfig;
import com.ledgercore.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for the response an authenticated caller gets from an ADMIN-only endpoint
 * without holding the ADMIN role. It used to be 500 PERSISTENCE_ERROR, because the catch-all
 * exception handler swallowed the access-denied exception raised by {@code @PreAuthorize}.
 *
 * <p>The real security filter chain, JWT filter and exception handler are used; only token
 * verification and the services behind the controllers are stubbed.</p>
 */
@WebMvcTest(controllers = {AuditController.class, UserAdminController.class})
@Import(SecurityConfig.class)
class AdminAuthorizationResponseTest {

    private static final String CUSTOMER_TOKEN = "customer-token";
    private static final String TELLER_TOKEN = "teller-token";
    private static final String ADMIN_TOKEN = "admin-token";
    private static final String BAD_TOKEN = "bad-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AuditService auditService;
    @MockitoBean
    private AuthzService authzService;

    @BeforeEach
    void stubTokenVerification() {
        when(jwtService.verifyAccessToken(CUSTOMER_TOKEN))
                .thenReturn(new AuthPrincipal(UUID.randomUUID(), Role.CUSTOMER));
        when(jwtService.verifyAccessToken(TELLER_TOKEN))
                .thenReturn(new AuthPrincipal(UUID.randomUUID(), Role.TELLER));
        when(jwtService.verifyAccessToken(ADMIN_TOKEN))
                .thenReturn(new AuthPrincipal(UUID.randomUUID(), Role.ADMIN));
        when(jwtService.verifyAccessToken(BAD_TOKEN))
                .thenThrow(DomainException.authentication("Invalid or expired access token."));
    }

    @Test
    void customerReadingTheAuditTrailGetsForbiddenNotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(CUSTOMER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTHORIZATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Insufficient permission."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(auditService);
    }

    @Test
    void tellerReadingTheAuditTrailGetsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(TELLER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTHORIZATION_ERROR"));

        verifyNoInteractions(auditService);
    }

    @Test
    void customerChangingARoleGetsForbiddenAndNothingIsChanged() throws Exception {
        mockMvc.perform(put("/api/v1/users/{id}/role", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(CUSTOMER_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTHORIZATION_ERROR"));

        verifyNoInteractions(authzService);
    }

    @Test
    void adminIsStillAllowed() throws Exception {
        when(auditService.query(any(), any(), any(), anyLong(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        UUID target = UUID.randomUUID();
        mockMvc.perform(put("/api/v1/users/{id}/role", target)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"TELLER\"}"))
                .andExpect(status().isOk());

        verify(authzService).changeUserRole(any(AuthPrincipal.class), eq(target), eq("TELLER"));
    }

    @Test
    void missingOrInvalidTokenIsStillUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_ERROR"));

        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(BAD_TOKEN)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_ERROR"));
    }

    @Test
    void unexpectedFailuresAreStillReportedAsTheGenericServerError() throws Exception {
        when(auditService.query(any(), any(), any(), anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("PERSISTENCE_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
