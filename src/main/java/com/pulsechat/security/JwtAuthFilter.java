package com.pulsechat.security;

import com.pulsechat.repo.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

        // Let CORS preflight pass without authentication.
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }

        String header = req.getHeader("Authorization");

        // No token: let Spring Security decide whether this endpoint is public.
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }

        try {
            var claims = jwt.parse(header.substring(7));
            var user = users.findById(claims.getSubject()).orElse(null);

            // A syntactically valid JWT is not enough: the user must still exist.
            if (user == null) {
                SecurityContextHolder.clearContext();
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required.");
                return;
            }

            var authentication = new UsernamePasswordAuthenticationToken(
                    user.getId(),
                    null,
                    List.of(
                            new SimpleGrantedAuthority(
                                    "ROLE_" + user.getRole().name()
                            )
                    )
            );

            req.setAttribute("user", user);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            chain.doFilter(req, res);

        } catch (Exception ex) {
            // Expired, malformed, or wrongly signed JWTs must be a clean 401.
            // The frontend can then discard the stale session and ask the user
            // to join again instead of remaining stuck on a reconnect loop.
            SecurityContextHolder.clearContext();
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required.");
        }
    }
}
