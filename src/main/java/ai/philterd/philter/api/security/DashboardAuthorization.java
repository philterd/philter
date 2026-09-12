package ai.philterd.philter.api.security;

import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import ai.philterd.philter.data.entities.UserEntity;
import org.bson.types.ObjectId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Rechecks administrators at settings and signing mutation boundaries, against the database rather
 * than against whatever the caller was told earlier in the request.
 *
 * <p>The account checks apply to every channel. The session checks on top of them apply only to a
 * dashboard request, because an API request has no dashboard principal and no Vaadin session to
 * satisfy; its own controller authorization and scope govern instead.
 */
public final class DashboardAuthorization {
    private DashboardAuthorization() { }

    public static void requireAdministrator(MongoClient client, ObjectId actingUserId) {
        final var auth = SecurityContextHolder.getContext().getAuthentication();
        // No request context at all: startup and background work have no caller to recheck.
        if (auth == null) return;
        // A caller that is present but not authenticated was refused before this check grew a
        // non-dashboard branch, and still is.
        if (!auth.isAuthenticated() || actingUserId == null) {
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
        // The account must still be an active administrator whatever the channel was.
        if (!"admin".equalsIgnoreCase(document.getString("role")) || user.isDeactivated() || user.isMfaLocked()) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }
        if (auth.getPrincipal() instanceof DashboardPrincipal principal) {
            if (!principal.matches(user) || user.isMfaEnabled() && !ai.philterd.philter.views.MfaChallengeView.isSatisfied(
                    com.vaadin.flow.server.VaadinSession.getCurrent(), user)) {
                throw new AccessDeniedException("Current administrator authorization required.");
            }
        }
    }
}
