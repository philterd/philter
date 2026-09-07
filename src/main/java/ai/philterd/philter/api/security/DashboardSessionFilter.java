package ai.philterd.philter.api.security;

import ai.philterd.philter.data.services.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Checks every dashboard request, including RPCs from already-open components. */
public final class DashboardSessionFilter extends OncePerRequestFilter {
    private final UserService users;
    public DashboardSessionFilter(UserService users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            final var user = users.findByUsername(auth.getName());
            if (!(auth.getPrincipal() instanceof DashboardPrincipal principal) || !principal.matches(user)) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false) != null) request.getSession(false).invalidate();
                response.sendError(401, "Account security changed. Sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
