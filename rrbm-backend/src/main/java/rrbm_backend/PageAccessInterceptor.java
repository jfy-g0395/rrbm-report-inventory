package rrbm_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * GAP S12-01 fix: enforces the users.allowed_pages column server-side.
 *
 * Maps each /api/** route to a page key (matching the frontend's viewToPageKey() function
 * in app.js) and returns 403 when the authenticated user's allowedPages JSON array does
 * not include that key.
 *
 * Bypass rules (pass-through without checking):
 *   - Route has no matching page key (settings, auth, etc.)
 *   - No Authorization header or token cannot be parsed
 *   - User not found in the database
 *   - User is SUPER_ADMIN (unrestricted access)
 *   - User's allowedPages is null (legacy / unrestricted)
 */
@Component
public class PageAccessInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Rule(String prefix, String pageKey) {}

    // Order matters: more-specific prefixes must come before their shallower siblings.
    private static final List<Rule> RULES = List.of(
        new Rule("/api/products/delivery",        "receive-stocks"),
        new Rule("/api/reports/rejected-items",   "receive-stocks"),
        new Rule("/api/reports/close-daily",      "daily-reports"),
        new Rule("/api/reports/daily-status",     "daily-reports"),
        new Rule("/api/reports/daily/",           "daily-reports"),
        new Rule("/api/reports/range",            "daily-reports"),
        new Rule("/api/reports/monthly/",         "reports"),
        new Rule("/api/reports/delivery-reports", "delivery-reports"),
        new Rule("/api/reports",                  "daily-reports"),
        new Rule("/api/orders/collections",          "collections"),
        new Rule("/api/orders/batch-mark-collected", "collections"),
        new Rule("/api/orders",                   "orders"),
        new Rule("/api/stock-transfers",          "delivery-schedule"),
        new Rule("/api/products",                 "inventory"),
        new Rule("/api/purchase-orders",          "purchase-orders"),
        new Rule("/api/suppliers",                "suppliers"),
        new Rule("/api/payables",                 "payables"),
        new Rule("/api/expenses",                 "expenses"),
        new Rule("/api/expense-categories",       "expenses"),
        new Rule("/api/users",                    "employees"),
        new Rule("/api/employees",                "employee-201"),
        new Rule("/api/activity-log",             "activity-log"),
        new Rule("/api/agents",                   "agents"),
        new Rule("/api/resellers",                "resellers"),
        new Rule("/api/dashboard",               "dashboard"),
        new Rule("/api/import",                  "import"),
        new Rule("/api/transactions",            "ledger"),
        new Rule("/api/cash-flow",               "cash-flow"),
        new Rule("/api/commission",               "reports")
    );

    public PageAccessInterceptor(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        String pageKey = resolvePageKey(path);
        if (pageKey == null) return true;

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return true;

        Long userId;
        try { userId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception e) { return true; }
        if (userId == null) return true;

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return true;

        if ("SUPER_ADMIN".equals(user.getRole())) return true;
        if (user.getAllowedPages() == null) return true;

        List<String> allowed;
        try {
            allowed = mapper.readValue(user.getAllowedPages(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) { return true; }

        if (allowed.contains(pageKey)) return true;

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"status\":403,\"message\":\"Access to this page is not permitted for your account\"}");
        return false;
    }

    private String resolvePageKey(String path) {
        for (Rule rule : RULES) {
            if (path.startsWith(rule.prefix())) return rule.pageKey();
        }
        return null;
    }
}
