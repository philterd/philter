package ai.philterd.philter.api.security;

import ai.philterd.philter.data.entities.UserEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import java.util.List;

/** Captures the security version at password authentication, never at a later UI request. */
public final class DashboardPrincipal extends User {
    private final String userId;
    private final long securityVersion;

    public DashboardPrincipal(UserEntity user, boolean locked) {
        super(user.getUsername(), user.getPassword(), !user.isDeactivated(), true, true, !locked,
                List.of(new SimpleGrantedAuthority("ROLE_" + (user.getRole() == null ? "USER" : user.getRole().toUpperCase(java.util.Locale.ROOT)))));
        userId = user.getId().toHexString();
        securityVersion = user.getSecurityVersion();
    }

    public boolean matches(UserEntity user) {
        return user != null && !user.isDeactivated() && !user.isMfaLocked() && userId.equals(user.getId().toHexString())
                && securityVersion == user.getSecurityVersion();
    }
}
