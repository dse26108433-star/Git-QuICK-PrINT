package edu.campus.print.config;

import edu.campus.print.common.RateLimitFilter;
import edu.campus.print.repo.AgentRepository;
import edu.campus.print.security.AgentAuthenticationFilter;
import edu.campus.print.security.AgentTokenService;
import edu.campus.print.security.CounterAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Three kinds of caller, kept apart:
 *
 *   /agent/**            the Xerox center PC, with its enrolled secret
 *   /api/v1/counter/**   Xerox center staff, with the counter password
 *   /api/v1/shop,
 *   /api/v1/orders/**    students. No login: each order has a private key
 *                        that only the student's device holds.
 *   /api/v1/payments/upi/alerts   the Xerox center's phone forwarding bank
 *                        SMS (CampusPay), with its own secret token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** The Xerox center PC. */
    @Bean
    @Order(1)
    SecurityFilterChain agentChain(HttpSecurity http, AgentTokenService tokens, AgentRepository agents)
            throws Exception {
        http.securityMatcher("/agent/**")
            .csrf(c -> c.disable())
            .cors(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers("/agent/v1/token").permitAll()
                    .anyRequest().hasAuthority("ROLE_AGENT"))
            .addFilterBefore(new AgentAuthenticationFilter(tokens, agents),
                    UsernamePasswordAuthenticationFilter.class)
            // Expired token -> 401, so the PC fetches a fresh one and retries.
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .headers(h -> h.frameOptions(f -> f.deny()));
        return http.build();
    }

    /** Students (public, no login) and counter staff (password). */
    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, RateLimitFilter rateLimit, CounterProperties counter)
            throws Exception {
        http.csrf(c -> c.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/counter/**").hasAuthority("ROLE_COUNTER")
                    .requestMatchers("/api/v1/shop", "/api/v1/orders", "/api/v1/orders/**").permitAll()
                    // CampusPay bank messages: UpiAlertController checks UPI_ALERT_TOKEN itself.
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/upi/alerts", "/api/v1/payments/upi/heartbeat").permitAll()
                    .anyRequest().denyAll())
            .addFilterBefore(new CounterAuthFilter(counter), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimit, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .headers(h -> h
                    .frameOptions(f -> f.deny())
                    .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                    .contentTypeOptions(Customizer.withDefaults()));
        return http.build();
    }

    /** RateLimitFilter must only run inside the chain above, not as a global filter too. */
    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitServletRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${campus.cors.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        List<String> list = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (list.contains("*")) {
            c.setAllowedOriginPatterns(List.of("*"));
        } else {
            c.setAllowedOrigins(list);
        }
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type", "X-Order-Key", "X-Counter-Password"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        return src;
    }
}
