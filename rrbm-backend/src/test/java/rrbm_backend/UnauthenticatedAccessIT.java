package rrbm_backend;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S1 — 401 baseline. Proves the security filter chain rejects unauthenticated access to a
 * representative protected endpoint in <b>every</b> controller, both when the {@code Authorization}
 * header is absent and when it carries a garbage token.
 *
 * <p>Spring Security evaluates {@code /api/**} → {@code authenticated()} at the filter level, so an
 * unauthenticated request is rejected with 401 before handler resolution — independent of whether
 * the path needs query params. Endpoints explicitly made public (POST {@code /api/auth/login} and
 * GET {@code /api/expense-categories}) are intentionally excluded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnauthenticatedAccessIT {

    @Autowired private MockMvc mockMvc;

    /** One representative gated endpoint per controller. */
    private Stream<ProtectedRoute> protectedRoutes() {
        return Stream.of(
                new ProtectedRoute(HttpMethod.GET,  "/api/orders"),                       // OrderController
                new ProtectedRoute(HttpMethod.GET,  "/api/products"),                     // ProductController
                new ProtectedRoute(HttpMethod.GET,  "/api/agents"),                       // AgentController
                new ProtectedRoute(HttpMethod.GET,  "/api/expenses"),                     // ExpenseController
                new ProtectedRoute(HttpMethod.GET,  "/api/suppliers"),                    // SupplierController
                new ProtectedRoute(HttpMethod.GET,  "/api/purchase-orders"),             // PurchaseOrderController
                new ProtectedRoute(HttpMethod.GET,  "/api/payables"),                     // PayableController
                new ProtectedRoute(HttpMethod.GET,  "/api/transactions/accounting-summary"), // TransactionController
                new ProtectedRoute(HttpMethod.GET,  "/api/dashboard/stats"),             // DashboardController
                new ProtectedRoute(HttpMethod.GET,  "/api/reports/insights-summary"),    // Reports/DailyReportController
                new ProtectedRoute(HttpMethod.GET,  "/api/settings"),                     // SettingsController
                new ProtectedRoute(HttpMethod.GET,  "/api/settings/notification-emails"),// NotificationEmailController
                new ProtectedRoute(HttpMethod.GET,  "/api/activity-log/today"),          // ActivityLogController
                new ProtectedRoute(HttpMethod.GET,  "/api/commissions/periods"),         // CommissionController
                new ProtectedRoute(HttpMethod.GET,  "/api/delivery-reports"),            // DeliveryReportController
                new ProtectedRoute(HttpMethod.GET,  "/api/users"),                        // UserController
                new ProtectedRoute(HttpMethod.POST, "/api/auth/logout"),                  // AuthController (non-login)
                new ProtectedRoute(HttpMethod.POST, "/api/expense-categories")            // ExpenseCategoryController (write is gated)
        );
    }

    @ParameterizedTest(name = "[{index}] {0} → 401 without a token")
    @MethodSource("protectedRoutes")
    void noToken_returns401(ProtectedRoute route) throws Exception {
        mockMvc.perform(base(route))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "[{index}] {0} → 401 with a garbage token")
    @MethodSource("protectedRoutes")
    void garbageToken_returns401(ProtectedRoute route) throws Exception {
        mockMvc.perform(base(route)
                        .header("Authorization", "Bearer not-a-real-jwt.value.here"))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder base(ProtectedRoute route) {
        return request(route.method(), route.path());
    }

    /** (method, path) pair; toString drives the readable parameterized display name. */
    record ProtectedRoute(HttpMethod method, String path) {
        @Override public String toString() { return method + " " + path; }
    }
}
