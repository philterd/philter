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
package ai.philterd.philter.model;

import org.springframework.http.HttpStatus;

/**
 * The reasons an error response gives, as stable codes a client can act on. A refusal that has a more
 * specific reason gives it; otherwise the reason follows from the status ({@link #forStatus(int)}).
 */
public final class ErrorReasons {

    // Authentication (401).
    /** The key is missing, malformed, or unknown, or is a revoked long-lived key. */
    public static final String INVALID_CREDENTIALS = "invalid_credentials";
    /** The session key ended: it expired, was revoked, or signed out. Sign in again. */
    public static final String SESSION_EXPIRED = "session_expired";
    /** The key's user is deactivated. */
    public static final String USER_DEACTIVATED = "user_deactivated";

    // Authorization (403).
    /** The key does not hold the scope the endpoint requires. */
    public static final String MISSING_SCOPE = "missing_scope";
    /** The operation requires an administrator. */
    public static final String ADMIN_REQUIRED = "admin_required";
    /** The operation is turned off in this deployment. */
    public static final String FEATURE_DISABLED = "feature_disabled";
    /** The session key may only change its user's password until it is changed. */
    public static final String PASSWORD_CHANGE_REQUIRED = "password_change_required";
    /** The session key may only enroll its user in MFA until enrollment is confirmed. */
    public static final String MFA_ENROLLMENT_REQUIRED = "mfa_enrollment_required";
    /** The current password given is wrong. */
    public static final String WRONG_PASSWORD = "wrong_password";
    /** The MFA code given is wrong. */
    public static final String INVALID_CODE = "invalid_code";
    /** The user's MFA is locked after repeated bad codes; an administrator must unlock it. */
    public static final String MFA_LOCKED = "mfa_locked";
    /** The key cannot widen a key's scopes or create a key, or does not hold the scopes it asks for. */
    public static final String SCOPE_NOT_HELD = "scope_not_held";

    // Conflicts (409, 410).
    /** The caller cannot do this to their own user or to the key making the request. */
    public static final String SELF_ACTION_REFUSED = "self_action_refused";
    /** The user is the last active administrator. */
    public static final String LAST_ADMIN = "last_admin";
    /** MFA is not available in this deployment. */
    public static final String MFA_UNAVAILABLE = "mfa_unavailable";
    /** The user is already enrolled in MFA. */
    public static final String MFA_ALREADY_ENROLLED = "mfa_already_enrolled";
    /** The user is not enrolled in MFA. */
    public static final String MFA_NOT_ENROLLED = "mfa_not_enrolled";
    /** The state changed between reading and writing it, such as a password changed by another request. */
    public static final String CHANGED_CONCURRENTLY = "changed_concurrently";
    /** The thing already exists, such as a username already in use. */
    public static final String ALREADY_EXISTS = "already_exists";
    /** The key is not a session key, so it cannot sign out. */
    public static final String NOT_A_SESSION_KEY = "not_a_session_key";
    /** No webhook is set. */
    public static final String WEBHOOK_NOT_SET = "webhook_not_set";
    /** The signing key is managed outside Philter. */
    public static final String EXTERNALLY_MANAGED = "externally_managed";
    /** The document is still being redacted. */
    public static final String DOCUMENT_NOT_READY = "document_not_ready";
    /** The document's redaction failed, so there is nothing to download. */
    public static final String DOCUMENT_FAILED = "document_failed";
    /** The context has queued or running redactions. */
    public static final String CONTEXT_IN_USE = "context_in_use";

    // By status, when nothing more specific applies.
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String NOT_FOUND = "not_found";
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String NOT_ACCEPTABLE = "not_acceptable";
    public static final String PAYLOAD_TOO_LARGE = "payload_too_large";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String SERVICE_UNAVAILABLE = "service_unavailable";
    public static final String INTERNAL_ERROR = "internal_error";
    public static final String FORBIDDEN = "forbidden";
    public static final String CONFLICT = "conflict";

    private ErrorReasons() {
    }

    /** The reason for an error with this status when the refusal gives no more specific one. */
    public static String forStatus(final int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> INVALID_CREDENTIALS;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL_ERROR : HttpStatus.resolve(status) == null
                    ? INVALID_REQUEST : HttpStatus.resolve(status).name().toLowerCase(java.util.Locale.ROOT);
        };
    }

}
