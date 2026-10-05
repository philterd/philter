# Sign-in Security

Philter has no built-in user interface. A user interface that runs separately signs people in through Philter, and [Philter UI](https://github.com/philterd/philter-ui), in development, is being built to work this way: it sends a person's username and password to Philter and receives a session key for that person's Philter user. Philter stays the only user store, the user's role and scopes decide what the person may do, and the audit log names the person rather than a key the interface shares.

This page describes how sign-in is protected. For the endpoints, see the [Sign-in API](api_and_sdks/api/sign_in_api.md), the [Users API](api_and_sdks/api/users_api.md), and the [API Keys API](api_and_sdks/api/api_keys_api.md).

## Enabling password sign-in

Password sign-in is **disabled by default**, so a deployment that runs no user interface exposes no login endpoint. Enable it with [`PASSWORD_SIGN_IN_ENABLED=true`](settings.md#api-access). While it is disabled, `POST /api/sign-in` and `POST /api/sign-in/mfa` answer `404 Not Found`, whatever the request contains. It is an environment variable rather than an admin setting, so an administrator's API key cannot turn it on.

Automation does not sign in. It uses long-lived [API keys](account/api_keys.md), and users created for it need no password.

## Passwords

* **Rules.** At least 16 characters and at most 72 bytes in UTF-8, the most bcrypt reads. Stored as bcrypt hashes and never returned or audited.
* **Optional.** A user without a password cannot sign in. Every kind of failed sign-in (wrong password, unknown username, a user without a password, a deactivated user) gets the same `401` in about the same time, so sign-in does not reveal which usernames exist.
* **The first administrator.** The `admin` user starts without a password. Set it with the [bootstrap API key](account/api_keys.md#bootstrapping-an-api-key-for-automation) through [`PUT /api/users/admin/password`](api_and_sdks/api/users_api.md#set-or-reset-a-users-password). There is no bootstrap password.
* **Set by an administrator.** A password given when an administrator creates a user, or set or reset later by an administrator on another user, must be changed at that user's next sign-in, because someone else chose it. An administrator setting their own first password, as with the bootstrap key, is not asked to change it.
* **Changed by the user.** [`PUT /api/users/me/password`](api_and_sdks/api/users_api.md#change-your-own-password) requires the current password, and the new one must differ. An administrator's key cannot replace its own user's password without the current one, so a stolen key cannot take over its own account.

## First sign-in and resets

A user who must change their password, or must enroll in MFA, gets a session key that can only do that and sign out; any other request is refused with `403 Forbidden`. The [sign-in response](api_and_sdks/api/sign_in_api.md#sign-in) says which with `passwordChangeRequired` and `mfaEnrollmentRequired`. Changing the password, or confirming MFA enrollment, revokes the key, and the person signs in again.

## Session keys

A [session key](account/api_keys.md#session-keys) is the key issued at sign-in.

* **Scopes.** It holds every scope, with the user's role still deciding administrator access. It cannot create API keys, so a session cannot produce a long-lived credential that outlives it or a password reset.
* **Timeouts.** It expires after [`SESSION_KEY_IDLE_TIMEOUT_MINUTES`](settings.md#api-access) (default 30) without a request, or [`SESSION_KEY_MAX_LIFETIME_MINUTES`](settings.md#api-access) (default 720) after sign-in, whichever comes first.
* **Every instance agrees.** Each request with a session key is checked against the database, not a cache, so an expired or revoked session key is refused on every instance at once. Keep the instances' clocks synchronized; expiry is measured with each instance's clock.
* **Ending a session.** The holder signs out with [`DELETE /api/api-keys/current`](api_and_sdks/api/api_keys_api.md#sign-out). An administrator signs a person out everywhere with [`DELETE /api/users/{username}/session-keys`](api_and_sdks/api/api_keys_api.md#revoke-a-users-session-keys). Setting, changing, or resetting a password, and enrolling in MFA, also revoke the user's session keys. Long-lived keys are never affected.

## Multi-factor authentication

Philter supports TOTP codes from an authenticator app. See [Multi-factor authentication](api_and_sdks/api/users_api.md#multi-factor-authentication) for the endpoints.

* **Settings.** The `mfaAvailable` [setting](api_and_sdks/api/settings_api.md) lets users enroll, and `mfaRequired` makes every user who signs in enroll. A user who is already enrolled is always asked for a code, even if `mfaAvailable` is later turned off.
* **Enrollment.** It takes effect only once a code for the new secret is confirmed. The secret is encrypted at rest with `PHILTER_ENCRYPTION_KEY` and shown only when enrollment starts.
* **Sign-in.** For an enrolled user, the password returns a single-use challenge that expires in five minutes, not a key. The challenge and a code are exchanged for the key at [`POST /api/sign-in/mfa`](api_and_sdks/api/sign_in_api.md#complete-sign-in-with-a-code). A key is never issued on the password alone, and any attempt uses up the challenge.
* **Codes.** Each code is accepted once. Five consecutive bad codes lock the user until an administrator unlocks them or removes the enrollment.
* **Removal.** A user removes their own enrollment with a valid code, so a stolen key cannot turn MFA off. An administrator removes another user's, for a person who has lost their device.

## Lockout and rate limiting

* **Username lockout.** [`SIGN_IN_MAX_FAILURES`](settings.md#api-access) (default 5) failed sign-ins within [`SIGN_IN_LOCKOUT_MINUTES`](settings.md#api-access) (default 15) lock the username for that long. A locked username is refused before its password is checked, even if it is right, and the lock clears on its own. Attempts are counted before their passwords are checked, so parallel guesses get no more checks than the limit.
* **Rate limiting.** Each client address may make [`SIGN_IN_RATE_LIMIT_PER_MINUTE`](settings.md#api-access) (default 20) sign-in requests a minute. The address follows the [trusted-proxy](settings.md#api-access) rules; set `TRUSTED_PROXIES` to your actual proxies if clients reach Philter directly from a private network.
* **Across instances.** With a shared [Valkey/Redis cache](caching.md), both limits are shared by every instance. With the default in-memory cache they are **per instance**, so behind a load balancer an attacker gets each instance's allowance and can evade them. Use a shared cache for any deployment with more than one instance.

See [Lockout and rate limiting](api_and_sdks/api/sign_in_api.md#lockout-and-rate-limiting) for the responses.

## Running a user interface

A user interface that signs people in through Philter should:

* Keep each person's session key on its own server, never in the browser.
* End its own session for the person when Philter rejects the key, which happens when the key expires or is revoked.
* Pass the password straight to Philter, and never store or log it.
* Serve its users over HTTPS and reach Philter over HTTPS.
* Sign the person out with `DELETE /api/api-keys/current` when they sign out of the interface.

## Settings

| Setting | Where | Default |
|---------|-------|---------|
| `PASSWORD_SIGN_IN_ENABLED` | [Environment](settings.md#api-access) | `false` |
| `SESSION_KEY_IDLE_TIMEOUT_MINUTES` | [Environment](settings.md#api-access) | `30` |
| `SESSION_KEY_MAX_LIFETIME_MINUTES` | [Environment](settings.md#api-access) | `720` |
| `SIGN_IN_MAX_FAILURES` | [Environment](settings.md#api-access) | `5` |
| `SIGN_IN_LOCKOUT_MINUTES` | [Environment](settings.md#api-access) | `15` |
| `SIGN_IN_RATE_LIMIT_PER_MINUTE` | [Environment](settings.md#api-access) | `20` |
| `mfaAvailable` | [Settings API](api_and_sdks/api/settings_api.md) | `false` |
| `mfaRequired` | [Settings API](api_and_sdks/api/settings_api.md) | `false` |

## Audit events

None of these records a password, a password hash, an MFA secret, or a code. They are security events and cannot be switched off. See [Auditing](auditing.md).

| Event | Recorded when |
|-------|---------------|
| `sign_in_succeeded` | A person signed in. |
| `sign_in_failed` | A sign-in or MFA code was refused. |
| `sign_in_locked` | Repeated failures locked a username. |
| `sign_in_rate_limited` | A client address went over the rate limit, once per minute. |
| `user_password_set` | A user without a password was given one. |
| `user_password_changed` | A user changed their own password. |
| `user_password_reset` | An administrator replaced a user's password. |
| `user_mfa_enrolled` | A user confirmed MFA enrollment. |
| `user_mfa_removed` | A user's MFA was removed. |
| `user_mfa_locked` | Five bad codes locked a user's MFA. |
| `user_mfa_unlocked` | An administrator unlocked a user's MFA. |
| `api_key_created` | A session key was issued, with `session: true` in the details. |
| `api_key_expired` | A session key passed its idle timeout or maximum lifetime. |
| `api_key_deleted` | A session key was revoked, with the reason in the details. |
