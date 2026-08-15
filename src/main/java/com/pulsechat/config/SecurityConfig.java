package com.pulsechat.config;

import com.pulsechat.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
public class SecurityConfig {

  @Value("${app.cors-origins}")
  private String origins;

  @Bean
  SecurityFilterChain filterChain(
          HttpSecurity http,
          JwtAuthFilter jwt
  ) throws Exception {

    http
            .csrf(csrf -> csrf.disable())

            .cors(cors ->
                    cors.configurationSource(corsConfigurationSource())
            )

            .sessionManagement(session ->
                    session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS
                    )
            )

            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/api/auth/**",
                            "/actuator/health",
                            "/ws/**"
                    ).permitAll()
                    .anyRequest().authenticated()
            )

            .addFilterBefore(
                    jwt,
                    UsernamePasswordAuthenticationFilter.class
            );

    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(
            Arrays.stream(origins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList()
    );

    configuration.setAllowedMethods(Arrays.asList(
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS"
    ));

    configuration.setAllowedHeaders(Arrays.asList("*"));

    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

    source.registerCorsConfiguration(
            "/**",
            configuration
    );

    return source;
  }
}