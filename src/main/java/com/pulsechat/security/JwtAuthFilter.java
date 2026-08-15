package com.pulsechat.security;

import com.pulsechat.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final UserRepository users;

    public JwtAuthFilter(
            JwtService jwt,
            UserRepository users
    ) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {

        // Allow CORS preflight requests to pass through
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String h = req.getHeader("Authorization");

        if (h != null && h.startsWith("Bearer ")) {

            try {
                var c = jwt.parse(h.substring(7));

                var u = users.findById(c.getSubject()).orElse(null);

                if (u != null) {

                    var auth =
                            new UsernamePasswordAuthenticationToken(
                                    u.getId(),
                                    null,
                                    List.of(
                                            new SimpleGrantedAuthority(
                                                    "ROLE_" + u.getRole().name()
                                            )
                                    )
                            );

                    req.setAttribute("user", u);

                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext()
                            .setAuthentication(auth);
                }

            } catch (Exception ignored) {
            }
        }

        chain.doFilter(req, res);
    }
}