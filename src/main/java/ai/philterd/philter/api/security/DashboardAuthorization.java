package ai.philterd.philter.api.security;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import ai.philterd.philter.data.entities.UserEntity;
import org.bson.types.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Rechecks dashboard administrators at settings and signing mutation boundaries. */
public final class DashboardAuthorization {
    private DashboardAuthorization() { }

    public static void requireAdministrator(MongoClient client, ObjectId actingUserId) {
        final var auth = SecurityContextHolder.getContext().getAuthentication();
        // Stateless API operations have their own controller authorization; startup has no session.
        if (auth == null) return;
        if (!auth.isAuthenticated() || !(auth.getPrincipal() instanceof DashboardPrincipal principal) || actingUserId == null) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }
        final var document = client.getDatabase("philter").getCollection("users")
                .find(Filters.eq("_id", actingUserId)).first();
        if (document == null) throw new AccessDeniedException("Account no longer exists.");
        final var user = new UserEntity();
        user.setId(document.getObjectId("_id"));
        user.setSecurityVersion(document.getLong("security_version"));
        user.setDeactivated(document.getBoolean("deactivated", false));
        user.setMfaLocked(document.getBoolean("mfa_locked", false));
        user.setMfaEnabled(document.getBoolean("mfa_enabled", false));
        if (!principal.matches(user) || !"admin".equalsIgnoreCase(document.getString("role"))
                || user.isMfaEnabled() && !ai.philterd.philter.views.MfaChallengeView.isSatisfied(
                        com.vaadin.flow.server.VaadinSession.getCurrent(), user)) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }
    }
}
