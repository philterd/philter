/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.data.services;

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.utils.PathSafeNames;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Users: their accounts, roles, passwords, multi-factor authentication, and webhook settings.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A username is unique, including among deactivated users: creating a user with a name already in use
 *   is refused and writes nothing.</li>
 *   <li>The last active administrator cannot be demoted or deactivated.</li>
 *   <li>Failed MFA attempts are counted atomically, so concurrent wrong codes are all counted toward the
 *   lock.</li>
 *   <li>A password change takes effect only if the password has not been changed by another request since
 *   it was checked; otherwise nothing is written.</li>
 *   <li>Secret fields are stored encrypted, and what is written is read back unchanged.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping users, sort by the fields
 *   the API offers, and search usernames as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface UserService {

    /** One {@code @}, something on each side, a dot in the domain, and no whitespace. */
    java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** The role that grants administrator rights. */
    String ROLE_ADMIN = "admin";

    /** The role every other user has. */
    String ROLE_USER = "user";

    String LAST_ADMIN_MESSAGE =
            "This is the last active administrator. Make another user an administrator first.";

    /** Fewer characters are refused. */
    int MIN_PASSWORD_CHARACTERS = 16;

    /** bcrypt reads at most 72 bytes, so a longer password would be silently truncated. */
    int MAX_PASSWORD_BYTES = 72;

    /** The longest email address accepted, the most an address can be in practice (RFC 5321). */
    int MAX_EMAIL_LENGTH = 254;

    /** Consecutive bad codes that lock a user's MFA until an administrator unlocks it. */
    int MAX_MFA_ATTEMPTS = 5;

    /** The outcome of checking an MFA code. */
    enum MfaCheck { ACCEPTED, REFUSED, LOCKED }

    /**
     * Looks up an <strong>active</strong> (not deactivated) user by username, for actions that grant a user
     * access: signing in and creating an API key. Use {@link #findAnyByUsername(String)} to resolve a user
     * whose data is being reached, such as the cross-user {@code owner} parameter, or to detect a username
     * that is already taken (including by a deactivated account), and {@link #findOneById(String)} or
     * {@link #findUsernamesByIds(Collection)} to resolve a deactivated user for audit and ledger display.
     */
    UserEntity findByUsername(final String username);

    /**
     * Counts a password sign-in attempt for the user, before the password is checked, in a window that begins at the
     * first attempt and lasts {@code windowMillis}: one atomic update, so parallel attempts are all counted. Stored with
     * the user, so it holds across instances and cannot be pushed out of a cache.
     *
     * @return this attempt's number in the window, or 0 when there is no such user.
     */
    long beginSignInAttempt(final String userId, final long nowMillis, final long windowMillis);

    /** Whether the user's password sign-in is locked at {@code nowMillis}. */
    boolean isSignInLocked(final String userId, final long nowMillis);

    /** Locks the user's password sign-in until {@code untilMillis}. */
    void lockSignIn(final String userId, final long untilMillis);

    /** Clears the user's count of sign-in attempts, after a sign-in whose password was right. */
    void resetSignInAttempts(final String userId);

    /**
     * Looks up a user by username regardless of deactivation state. Used when creating an account to
     * reject a username that already belongs to any user, active or deactivated: a deactivated account
     * keeps its username reserved so it can be reactivated rather than duplicated.
     */
    UserEntity findAnyByUsername(final String username);

    /**
     * Returns whether the user with the given id is deactivated, fetching only the deactivation flag.
     * Used on the API authentication hot path to reject keys whose owning user has been deactivated,
     * so deactivation and reactivation take effect immediately without touching the user's API keys.
     * A missing user is treated as deactivated (no active access).
     */
    boolean isDeactivated(final String userId);

    /**
     * Looks up a user by id, including deactivated users, so that audit and ledger entries that
     * reference a deactivated user still resolve to the retained record.
     */
    UserEntity findOneById(final String id);

    /**
     * Resolves several user ids to their usernames in a single query, returning a map of id to
     * username. Used by the admin "All ..." views to label each row with its owner without issuing one
     * lookup per row. Ids with no matching user are simply absent from the returned map.
     */
    Map<String, String> findUsernamesByIds(final Collection<String> ids);

    ServiceResponse createUser(final String requestId, final String username, final String role,
            final PolicyDataService policyService, final ContextDataService contextService,
            final String source);

    ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
            final PolicyDataService policyService, final ContextDataService contextService,
            final String source);

    /**
     * Creates a user, with a default policy and context. {@code actingUserId} is recorded as the principal
     * of the {@code user_created} audit event, or the new user itself when null (startup), and
     * {@code actingApiKeyId}, if any, in the details. The user has no password, so it can only use API keys.
     */
    ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
            final PolicyDataService policyService, final ContextDataService contextService,
            final String source, final String actingUserId, final String actingApiKeyId);

    /**
     * As above, with an optional password, which the user must change at next sign-in because someone
     * else chose it. Setting it is audited as {@code user_password_set}, without the password.
     *
     * @throws IllegalArgumentException if the password is not acceptable (see {@link #passwordProblem(String)}),
     *         or the username cannot be used in a request path (see {@link PathSafeNames}).
     */
    ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
            final String password, final PolicyDataService policyService,
            final ContextDataService contextService, final String source,
            final String actingUserId, final String actingApiKeyId);

    /**
     * Lists a page of users sorted by email. When {@code includeDeactivated} is false, deactivated
     * users are excluded; when true, every user is returned, deactivated ones included.
     */
    /**
     * A page of users, deactivated ones included, whose usernames contain {@code q}, with the given role
     * and active state when they are given, and their total.
     */
    Listings.Page<UserEntity> list(final String q, final String role, final Boolean active,
            final Listings.Sort sort, final int offset, final int limit);

    /** Counts all users, including deactivated ones. */
    int count();

    /** Counts users; when {@code includeDeactivated} is false, deactivated users are excluded. */
    int count(final boolean includeDeactivated);

    ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source);

    /**
     * Sets a user's role, recording {@code actingUserId} as the audit principal (the target user when
     * null) and {@code actingApiKeyId}, if any, in the details. Refuses to demote the last active
     * administrator, also when several administrators are demoted or deactivated at once, on any instance: at
     * least one active administrator always remains.
     */
    ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source, final String actingUserId, final String actingApiKeyId);

    /**
     * Why an email address is not acceptable, or {@code null} when it is. No address at all is
     * acceptable: a user need not have one. The check is deliberately loose, catching typos rather than
     * proving the address can receive mail.
     */
    static String emailProblem(final String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        final String trimmed = email.trim();
        if (trimmed.length() > MAX_EMAIL_LENGTH) {
            return "The email address must be at most " + MAX_EMAIL_LENGTH + " characters.";
        }
        if (!EMAIL_PATTERN.matcher(trimmed).matches()) {
            return "The email address is not valid.";
        }
        return null;
    }

    /**
     * Sets or clears a user's email address, recording {@code actingUserId} as the audit principal (the
     * user when null) and {@code actingApiKeyId}, if any, in the details. The address itself is not
     * recorded in the audit log.
     */
    ServiceResponse setEmail(final String requestId, final UserEntity userEntity, final String email,
            final String source, final String actingUserId, final String actingApiKeyId);

    /** Counts active (not deactivated) administrators. */
    long countActiveAdmins();

    /**
     * Whether removing this user's administrator rights would leave no active administrator now. It is advice
     * only: {@link #setUserRole} and {@link #deactivateUser} make the check again atomically with their change.
     */
    boolean isLastActiveAdmin(final UserEntity userEntity);

    /** Returns the key assigned at account creation; missing key material is a configuration error. */
    String ensureFpeKey(final UserEntity userEntity);

    /**
     * Deactivates a user. The account is marked deactivated (with the time of deactivation) but the
     * user record and <strong>all</strong> of the user's data (API keys, contexts, custom lists,
     * policies, redact lists, and redaction ledger) are retained, so the account can be reactivated
     * later (see {@link #reactivateUser(String, UserEntity, String)}) and so audit and ledger entries
     * that reference the user id still resolve to a name.
     *
     * <p>A deactivated user holds no active access: it is excluded from {@link #findByUsername(String)},
     * which sign-in consults, and
     * the API authentication filter rejects its API keys by checking {@link #isDeactivated(String)}
     * live. The keys themselves are left untouched so reactivation restores access immediately without
     * resurrecting keys the user had separately deleted.
     *
     * <p>Crucially, deactivation never cascades to the user's data. Governance evidence in particular
     * (the user's policies and redaction ledger) is retained and stays resolvable to the retained user
     * record, so no admin action can silently destroy it. The audit event records that retention.
     */
    ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source);

    /**
     * Deactivates a user as above, recording {@code actingUserId} as the audit principal (the target user
     * when null) and {@code actingApiKeyId}, if any, in the details. Refuses to deactivate the last
     * active administrator, also when several administrators are demoted or deactivated at once, on any instance:
     * at least one active administrator always remains.
     */
    ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source, final String actingUserId, final String actingApiKeyId);

    /**
     * Reactivates a previously deactivated user, restoring API access. The user's data was
     * never removed on deactivation, so reactivation returns the account to exactly its prior state.
     */
    ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source);

    /**
     * Reactivates a user as above, recording {@code actingUserId} as the audit principal (the target user
     * when null) and {@code actingApiKeyId}, if any, in the details.
     */
    ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source, final String actingUserId, final String actingApiKeyId);

    /** Whole-record saves could overwrite a concurrent change to another field; update fields individually. */
    /**
     * Validates and saves a user's webhook URL and secret, then audits {@code webhook_configured} with
     * {@code actingUserId} as the principal (the user when null) and {@code actingApiKeyId}, if any, in
     * the details. The URL and secret are never audited.
     *
     * @param allowlist The administrator's webhook destination allowlist, or {@code null} for none.
     */
    ServiceResponse setWebhook(final String requestId, final UserEntity user, final String url,
            final String secret, final String allowlist, final String source,
            final String actingUserId, final String actingApiKeyId);

    /** Removes a user's webhook and audits {@code webhook_removed}, recording the acting principal as above. */
    ServiceResponse removeWebhook(final String requestId, final UserEntity user, final String source,
            final String actingUserId, final String actingApiKeyId);

    /**
     * Why a password is not acceptable, or {@code null} when it is: at least
     * {@value #MIN_PASSWORD_CHARACTERS} characters and at most {@value #MAX_PASSWORD_BYTES} UTF-8 bytes.
     */
    static String passwordProblem(final String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARACTERS) {
            return "The password must be at least " + MIN_PASSWORD_CHARACTERS + " characters.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return "The password must be at most " + MAX_PASSWORD_BYTES + " bytes in UTF-8.";
        }
        return null;
    }

    /**
     * The active user with this username and password, or {@code null}. A missing user, a deactivated
     * user, a user without a password, and a wrong password all return {@code null}, and each runs one
     * bcrypt comparison, so neither the result nor the time taken tells a caller which usernames exist.
     */
    UserEntity authenticate(final String username, final String plainPassword);

    /** Whether the plaintext matches the user's password. Always false for a user without one. */
    boolean passwordMatches(final UserEntity user, final String plainPassword);

    /**
     * Changes the user's own password, which requires the current one, and clears any forced change.
     * Audited as {@code user_password_changed}, without either password. The caller revokes the user's
     * session keys.
     *
     * @return a failure with status 400 (the new password is unacceptable or unchanged), 403 (the current
     * password is wrong), or 409 (the user has no password, or it changed concurrently).
     */
    ServiceResponse changeOwnPassword(final String requestId, final UserEntity user, final String currentPassword,
            final String newPassword, final String source, final String actingApiKeyId);

    /**
     * Sets a user's password without the current one, as an administrator does. Audited as
     * {@code user_password_reset} when it replaces a password and {@code user_password_set} when the user
     * had none, naming {@code actingUserId} and {@code actingApiKeyId}, never the password. The caller
     * revokes the user's session keys.
     *
     * @param changeRequired whether the user must change it at next sign-in.
     * @return a failure with status 400 if the password is not acceptable, or 409 if it changed concurrently.
     */
    ServiceResponse setPassword(final String requestId, final UserEntity user, final String newPassword,
            final boolean changeRequired, final String source,
            final String actingUserId, final String actingApiKeyId);

    /**
     * Starts MFA enrollment: generates a secret and holds it as pending until
     * {@link #confirmMfaEnrollment} sees a valid code for it, so a half-finished enrollment never applies
     * at sign-in. Starting again replaces the pending secret.
     *
     * @return the secret, shown to the user once to set up an authenticator app, or {@code null} if the
     * user is already enrolled.
     */
    String startMfaEnrollment(final UserEntity user);

    /** The {@code otpauth://} URI an authenticator app reads, as a QR code, to set up the secret. */
    String mfaOtpauthUri(final UserEntity user, final String secret);

    /**
     * Completes enrollment with a code for the pending secret, which then becomes the user's MFA secret.
     * Audited as {@code user_mfa_enrolled}. The caller revokes the user's session keys.
     *
     * @return a failure with status 400 (the code is not valid) or 409 (already enrolled, or no
     * enrollment was started).
     */
    ServiceResponse confirmMfaEnrollment(final String requestId, final UserEntity user, final String code,
            final String source, final String actingApiKeyId);

    /**
     * Checks an MFA code for an enrolled user. A code is accepted once: the write that accepts it
     * requires its time step to be later than the last one accepted, so a replay, or two requests
     * racing with the same code, fails. A refused code counts toward {@value #MAX_MFA_ATTEMPTS}
     * consecutive failures, which lock the user; an accepted one resets the count.
     */
    MfaCheck checkMfaCode(final String requestId, final UserEntity user, final String code, final String source);

    /**
     * Removes a user's MFA enrollment, including any pending one, and clears a lock. Audited as
     * {@code user_mfa_removed}, naming {@code actingUserId} (the user when null) and {@code actingApiKeyId}.
     *
     * @return a failure with status 409 if the user is not enrolled.
     */
    ServiceResponse removeMfa(final String requestId, final UserEntity user, final String source,
            final String actingUserId, final String actingApiKeyId);

    /**
     * Unlocks a user locked out of MFA and resets the count of bad codes. Audited as
     * {@code user_mfa_unlocked}, naming the acting administrator and API key.
     *
     * @return a failure with status 409 if the user is not locked.
     */
    ServiceResponse unlockMfa(final String requestId, final UserEntity user, final String source,
            final String actingUserId, final String actingApiKeyId);

    /**
     * Clears a user's password sign-in lock and count of failed sign-ins before the lockout period ends. Audited as
     * {@code sign_in_unlocked}, naming the acting administrator and API key.
     *
     * @return a failure with status 409 if the user's sign-in is not locked.
     */
    ServiceResponse unlockSignIn(final String requestId, final UserEntity user, final String source,
            final String actingUserId, final String actingApiKeyId);

}
