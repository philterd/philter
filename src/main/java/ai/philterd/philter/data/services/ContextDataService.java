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

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Each user's redaction contexts and their settings.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A context name is unique per user: creating a second context with the same name is refused and
 *   writes nothing.</li>
 *   <li>A user never has more than {@link #MAXIMUM_CONTEXTS_PER_USER} contexts, even when contexts are created
 *   concurrently.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping contexts, sort by the
 *   fields the API offers, and search names as case-insensitive plain text (see {@link Listings}).</li>
 *   <li>A context value is stored with its context: the first value stored under a key wins, even when stored
 *   concurrently, emptying the context's entries keeps it, and deleting the context removes it.</li>
 * </ul>
 */
public interface ContextDataService {

    int MAXIMUM_CONTEXTS_PER_USER = 10;

    /** Reasons a create is refused with 409, carried in the response's details. */
    String REASON_CONTEXT_EXISTS = "context_exists";

    String REASON_CONTEXT_LIMIT_REACHED = "context_limit_reached";

    /** The refusal of a disambiguation scope that is not one of the allowed values. */
    String DISAMBIGUATION_SCOPE_REFUSAL =
            "disambiguationScope must be " + ContextEntity.DISAMBIGUATION_SCOPE_VALUES + ".";

    /**
     * A stored context value key: letters, digits, {@code _}, {@code -}, and {@code %XX} escapes, as {@link
     * #valueKey(String)} produces.
     */
    Pattern VALUE_KEY = Pattern.compile("(?:[A-Za-z0-9_-]|%[0-9A-F]{2}){1,128}");

    /** The longest context value stored. */
    int MAX_VALUE_LENGTH = 256;

    /** The stored key of Phileas's random date shift, a whole number of days. */
    String DATE_SHIFT_KEY = "%00date-shift";

    /**
     * The smallest date shift an import accepts, in days either way: the least Phileas draws, so an import cannot
     * leave dates unchanged or nearly so.
     */
    long MIN_DATE_SHIFT_DAYS = 30;

    /** The largest date shift an import accepts, in days either way: about 100 years. */
    long MAX_DATE_SHIFT_DAYS = 36_525;

    /**
     * Why an imported context value is refused, or null when it is accepted. Only values Philter knows are
     * accepted, since a value Phileas cannot read would make every redaction that uses it fail.
     */
    static String refusalOfImportedValue(final String key, final String value) {
        if (!DATE_SHIFT_KEY.equals(key)) {
            return "The only key values may hold is " + DATE_SHIFT_KEY + ".";
        }
        final String refusal = "The " + DATE_SHIFT_KEY + " value must be a whole number of days, from "
                + MIN_DATE_SHIFT_DAYS + " to " + MAX_DATE_SHIFT_DAYS + " either way.";
        if (value == null || !value.matches("-?[0-9]{1,6}")) {
            return refusal;
        }
        final long days = Math.abs(Long.parseLong(value));
        return days >= MIN_DATE_SHIFT_DAYS && days <= MAX_DATE_SHIFT_DAYS ? null : refusal;
    }

    /**
     * The key a context value is stored under: {@code key} with every character other than a letter, digit,
     * {@code _}, or {@code -} percent-encoded, so it is a valid MongoDB field name. Phileas's date shift key,
     * {@code "\0date-shift"}, is stored as {@code %00date-shift}.
     */
    static String valueKey(final String key) {
        final StringBuilder encoded = new StringBuilder();
        for (final byte b : key.getBytes(StandardCharsets.UTF_8)) {
            final char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    ServiceResponse create(final String contextName, final String userId);

    ServiceResponse create(final String contextName, final String userId, final boolean disambiguation, final boolean ledger);

    /** As above, with a disambiguation scope: {@code document} or {@code context}, in any case. */
    ServiceResponse create(final String contextName, final String userId, final boolean disambiguation,
            final String disambiguationScope, final boolean ledger);

    /** The user's context with this name, or null when the user has none. */
    ContextEntity findOneByNameAndUserId(final String contextName, final String userId);

    /**
     * A page of the user's contexts, or every user's when {@code userId} is null, whose names contain
     * {@code q}, and their total.
     */
    Listings.Page<ContextEntity> list(final String userId, final String q, final Listings.Sort sort,
            final int offset, final int limit);

    int count(final String userId);

    ContextEntity findOne(final String contextName, final String userId);

    /**
     * Changes a context's settings. A {@code null} setting keeps its current value, so changing one never
     * changes another. The disambiguation scope is {@code document} or {@code context}, in any case. Audited
     * as {@code context_updated}, naming each setting whose value changed, the acting user, and the acting
     * API key.
     */
    ServiceResponse updateSettings(final String requestId, final String contextName, final String userId,
            final Boolean disambiguation, final String disambiguationScope,
            final Boolean ledger, final String actingUserId,
            final String actingApiKeyId);

    ServiceResponse emptyByName(final String contextName, final String userId);

    ServiceResponse deleteByName(final String contextName, final String userId);

    ServiceResponse deleteByName(final String contextName, final String requesterUserId, final boolean requesterIsAdmin);

    /**
     * The value the user's context holds under {@code key}, storing {@code value} first when it holds none. Of
     * concurrent calls for one key, exactly one stores its value and all return it.
     *
     * @param key A key matching {@link #VALUE_KEY}.
     * @return The stored value, or null when the user has no such context.
     */
    String putValueIfAbsent(final String userId, final String contextName, final String key, final String value);

    /**
     * Stores {@code value} under {@code key} in the user's context, replacing any value it holds.
     *
     * @param key A key matching {@link #VALUE_KEY}.
     * @return Whether the user has the context.
     */
    boolean putValue(final String userId, final String contextName, final String key, final String value);

}
