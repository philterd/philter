# Login Security

Philter protects the dashboard login with a forced password change for the default administrator and a temporary lockout after repeated failed logins. Both apply to the dashboard (UI) login, not to API key authentication (see [API Keys and Authentication](account/api_keys.md) for the API).

## Forced password change on first login

Philter seeds a default administrator account (`admin` / `admin`) the first time it starts. Because that password is well known, the account is flagged so that the password must be changed before the dashboard can be used.

When you sign in with the default password, Philter redirects you to a "Set a New Password" screen. You cannot reach any other dashboard page until you set a new password. The new password must:

* be at least 8 characters, and
* be different from the current password.

Once changed, the requirement is cleared and you continue to the dashboard normally. The password change is recorded in the [audit log](auditing.md) as a `user_password_changed` event.

> The default credentials still allow the first sign-in, so cloud marketplace images continue to boot without extra configuration. The forced change ensures the well-known default password cannot remain in use.

## Failed-login lockout

To resist password guessing, Philter temporarily locks a dashboard account after too many consecutive failed login attempts. While locked, login attempts for that username are rejected before the password is checked, even if the correct password is supplied. The lock clears automatically after the lockout window passes (or immediately after a successful login once the window expires).

The thresholds are configurable with environment variables:

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `LOGIN_MAX_ATTEMPTS` | Number of consecutive failed logins that triggers a lockout. | `5` |
| `LOGIN_LOCKOUT_SECONDS` | How long the lockout lasts, in seconds. | `900` (15 minutes) |

Failed and blocked logins are visible in the [audit log](auditing.md).

> The lockout counter is stored in Philter's cache. When a [Valkey/Redis cache](caching.md) is configured, the count is shared across all Philter instances, so the lockout is enforced consistently behind a load balancer. With the default in-memory cache, the count is per instance.

## See also

* [Dashboard](dashboard.md)
* [Auditing](auditing.md)
* [Caching](caching.md)
* [API Keys and Authentication](account/api_keys.md)
