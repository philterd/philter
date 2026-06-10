# Login Security

Philter protects the dashboard login with a forced password change for the default administrator, a temporary lockout after repeated failed logins, and optional multi-factor authentication. These apply to the dashboard (UI) login, not to API key authentication (see [API Keys and Authentication](account/api_keys.md) for the API).

## Password requirements

A dashboard password should be:

* **Long:** at least 16 characters (more is better).
* **Random:** a mix of upper and lowercase letters, numbers, and symbols, or a passphrase of 5 to 7 unrelated words.

The 16-character minimum is **enforced** wherever a password is set: the forced first-login change, a user changing their own password from **My Account**, an administrator creating a user, and an administrator changing a user's password on the **Admin** → **Users** tab. Each of these screens also asks you to confirm the new password by typing it twice, and links here. The randomness guidance is recommended rather than enforced, so that a long passphrase is accepted.

When an administrator creates a user, the New User dialog has a **Generate** button that fills in a strong random password (mixed letters, numbers, and symbols) meeting the requirements, which the administrator can reveal and share with the new user.

When you change **your own** password (the forced first-login change or My Account), the new password must also be **different from your current password**.

Passwords are stored only as bcrypt hashes, never in plaintext, and every change is recorded in the [audit log](auditing.md) as a `user_password_changed` event.

## Forced password change on first login

Philter seeds a default administrator account (`admin` / `admin`) the first time it starts. Because that password is well known, the account is flagged so that the password must be changed before the dashboard can be used.

When you sign in with the default password, Philter redirects you to a "Set a New Password" screen. You cannot reach any other dashboard page until you set a new password that meets the [password requirements](#password-requirements) above.

Once changed, the requirement is cleared and you continue to the dashboard normally.

> The default credentials still allow the first sign-in, so cloud marketplace images continue to boot without extra configuration. The forced change ensures the well-known default password cannot remain in use.

## Failed-login lockout

To resist password guessing, Philter temporarily locks a dashboard account after too many consecutive failed login attempts. While locked, login attempts for that username are rejected before the password is checked, even if the correct password is supplied. The lock clears automatically after the lockout window passes (or immediately after a successful login once the window expires).

The thresholds are configurable with environment variables:

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `LOGIN_MAX_ATTEMPTS` | Number of consecutive failed logins that triggers a lockout. | `5` |
| `LOGIN_LOCKOUT_SECONDS` | How long the lockout lasts, in seconds. | `900` (15 minutes) |

Failed and blocked logins are visible in the [audit log](auditing.md).

> **The lockout counter is stored in Philter's cache, so a shared cache is required behind a load balancer.** When a [Valkey/Redis cache](caching.md) is configured, the count is shared across all Philter instances and the lockout is enforced consistently. With the default in-memory cache the count is **per instance**, which means in a multi-instance deployment an attacker can evade the lockout entirely by spreading failed logins across instances, since each instance only ever sees a fraction of the attempts. Always set `CACHE_HOSTNAME` when running more than one instance.

## Session timeout

The dashboard signs a user out after a period of inactivity and returns them to the login page. Activity means real interaction with the dashboard (navigating, clicking, typing); the background keep-alive traffic the dashboard sends does not count, so a tab left open and untouched still times out.

The timeout is configurable with an environment variable:

| Environment Variable | Description | Default Value |
|----------------------|-------------|---------------|
| `SESSION_TIMEOUT_MINUTES` | Minutes of inactivity before the dashboard session is closed and the user is redirected to the login page. | `15` |

This applies to the dashboard (UI) session only. API key authentication is stateless and is not affected (see [API Keys and Authentication](account/api_keys.md)).

> **The session timeout is enforced per instance, in memory.** A dashboard session lives entirely on the Philter instance that created it; it is not serialized and is never stored in a [Valkey/Redis cache](caching.md), so unlike the [failed-login lockout](#failed-login-lockout) above this setting neither uses nor needs a shared cache and requires no coordination between instances. Each instance simply closes the idle sessions it is holding.

## Multi-factor authentication (MFA)

Philter supports time-based one-time-password (TOTP) MFA for the dashboard, compatible with standard authenticator apps (Google Authenticator, Authy, 1Password, and similar). MFA is **opt-in**: enabling it makes it available, and each user chooses whether to enroll. Only enrolled users are prompted for a code.

**Enable the feature (admin).** An administrator turns MFA on under Admin, Admin Settings, Multi-Factor Authentication. While off, the MFA enrollment option is not offered to users.

**Enroll (user).** With the feature enabled, a user opens My Account, MFA, scans the QR code (or types the setup key) into their authenticator app, and enters a generated code to confirm. Enrollment takes effect once a valid code is verified.

**At login.** An enrolled user signs in with their username and password as usual, then enters a code from their authenticator app to finish signing in. A user who is not enrolled signs in with just their password.

**Too many failed codes.** After 5 consecutive incorrect codes, the account is locked and the user cannot finish signing in. The lock does not expire on its own; an administrator must clear it. The lock is recorded in the [audit log](auditing.md) as a `user_mfa_locked` event.

**Unlock a locked account (admin).** An administrator clears the lock with Unlock on the Admin, Users tab. The user's enrollment is unchanged and they can enter a code again. (Disabling MFA for the user also clears the lock.)

**Reset a lost authenticator (admin).** If a user loses their authenticator, an administrator clears their enrollment with Disable MFA on the Admin, Users tab. The user can then enroll again. A user can also disable MFA on their own account from My Account, MFA.

Turning the feature off later does not remove existing enrollments: already-enrolled users keep being prompted until they (or an admin) disable their MFA. Each user's TOTP secret is encrypted at rest. MFA applies to the dashboard only; API key authentication is unaffected. Enabling and disabling MFA for an account is recorded in the [audit log](auditing.md).

## See also

* [Dashboard](dashboard.md)
* [Auditing](auditing.md)
* [Caching](caching.md)
* [API Keys and Authentication](account/api_keys.md)
