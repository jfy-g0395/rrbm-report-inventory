package rrbm_backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Enables Spring Security with a stateless JWT-based filter chain.
 *
 * Public endpoints (no token required):
 *   POST /api/auth/login
 *   OPTIONS /**  (CORS pre-flight — browsers send this before every cross-origin request)
 *
 * Everything else under /api/** requires a valid Bearer token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CORS is handled globally here — controllers keep @CrossOrigin for clarity,
            // but the security layer must also allow cross-origin requests.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Disable CSRF — not needed for a stateless REST API
            .csrf(csrf -> csrf.disable())

            // Never create an HTTP session; every request is authenticated via JWT
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                // CORS pre-flight must always pass through unauthenticated
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Login is the only open auth endpoint
                .requestMatchers("/api/auth/login").permitAll()
                // Reference data — no token needed
                .requestMatchers(HttpMethod.GET, "/api/expense-categories").permitAll()
                // Everything else under /api requires a valid JWT
                .requestMatchers("/api/**").authenticated()
                // Static files / anything else
                .anyRequest().permitAll()
            )

            // Return 401 (not 403) for requests that have no credentials at all —
            // 401 means "provide credentials", 403 means "credentials present but insufficient".
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(
                    (req, res, e) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))

            // Run our JWT filter before Spring's username/password filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Global CORS policy — allows the local frontend (and any origin during dev).
     * In production, replace allowedOriginPatterns with the exact domain(s).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
