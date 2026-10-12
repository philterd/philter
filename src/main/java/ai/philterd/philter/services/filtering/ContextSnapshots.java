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
package ai.philterd.philter.services.filtering;

import ai.philterd.philter.data.entities.ContextEntity;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * The context settings captured with an asynchronous redaction, so the redaction uses them as they were when the
 * document was submitted. The capture is part of the effective configuration, whose hash ledger entries record,
 * so its format is Philter's own and the same whatever the storage: MongoDB relaxed extended JSON, as Philter has
 * always written it, with ids as {@code {"$oid": ...}} and dates as {@code {"$date": ...}}.
 */
public final class ContextSnapshots {

    /** The latest date relaxed extended JSON writes as text; later ones, and earlier than 1970, are numbers. */
    private static final long LAST_TEXT_DATE = 253402300799999L;

    private ContextSnapshots() {
    }

    /** The context as captured. */
    public static String toJson(final ContextEntity context) {
        final StringBuilder json = new StringBuilder("{");
        if (context.getId() != null) {
            field(json, "_id").append(id(context.getId()));
        }
        field(json, "user_id").append(id(context.getUserId()));
        field(json, "context_name").append(string(context.getContextName()));
        field(json, "max_size").append(context.getMaxSize());
        field(json, "disambiguation").append(context.isDisambiguation());
        field(json, "disambiguation_scope").append(string(context.getDisambiguationScope()));
        field(json, "ledger").append(context.isLedger());
        field(json, "slot").append(context.getSlot() == null ? "null" : context.getSlot().toString());
        field(json, "ttl_in_hours").append(context.getTtlInHours());
        field(json, "timestamp").append(date(context.getTimestamp()));
        return json.append('}').toString();
    }

    /** The context a capture holds, with the defaults a missing setting has always had. */
    public static ContextEntity fromJson(final String json) {
        final JsonObject captured = JsonParser.parseString(json).getAsJsonObject();
        final ContextEntity context = new ContextEntity();
        context.setId(readId(captured.get("_id")));
        context.setUserId(readId(captured.get("user_id")));
        context.setContextName(readString(captured.get("context_name")));
        context.setMaxSize(present(captured.get("max_size")) ? captured.get("max_size").getAsInt() : ContextEntity.MAX_CONTEXT_SIZE);
        context.setDisambiguation(present(captured.get("disambiguation")) && captured.get("disambiguation").getAsBoolean());
        final String scope = readString(captured.get("disambiguation_scope"));
        context.setDisambiguationScope(scope != null ? scope : ContextEntity.DISAMBIGUATION_SCOPE_DOCUMENT);
        context.setLedger(present(captured.get("ledger")) && captured.get("ledger").getAsBoolean());
        context.setSlot(present(captured.get("slot")) ? captured.get("slot").getAsInt() : null);
        context.setTtlInHours(present(captured.get("ttl_in_hours")) ? captured.get("ttl_in_hours").getAsInt()
                : ContextEntity.DEFAULT_TTL_IN_HOURS);
        context.setTimestamp(readDate(captured.get("timestamp")));
        return context;
    }

    private static StringBuilder field(final StringBuilder json, final String name) {
        if (json.length() > 1) {
            json.append(", ");
        }
        return json.append(string(name)).append(": ");
    }

    private static String id(final String id) {
        return id == null ? "null" : "{\"$oid\": " + string(id) + "}";
    }

    private static String date(final Date date) {
        if (date == null) {
            return "null";
        }
        final long millis = date.getTime();
        if (millis < 0 || millis > LAST_TEXT_DATE) {
            return "{\"$date\": {\"$numberLong\": \"" + millis + "\"}}";
        }
        return "{\"$date\": \"" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(millis)
                .atOffset(ZoneOffset.UTC)) + "\"}";
    }

    /** A JSON string escaped as MongoDB's JSON writer escapes it. */
    static String string(final String value) {
        if (value == null) {
            return "null";
        }
        final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    switch (Character.getType(c)) {
                        case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                             Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER,
                             Character.OTHER_NUMBER, Character.SPACE_SEPARATOR, Character.CONNECTOR_PUNCTUATION,
                             Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                             Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                             Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                             Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> out.append(c);
                        default -> out.append(String.format("\\u%04x", (int) c));
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static boolean present(final JsonElement element) {
        return element != null && !element.isJsonNull();
    }

    private static String readString(final JsonElement element) {
        return present(element) ? element.getAsString() : null;
    }

    private static String readId(final JsonElement element) {
        return present(element) ? element.getAsJsonObject().get("$oid").getAsString() : null;
    }

    private static Date readDate(final JsonElement element) {
        if (!present(element)) {
            return null;
        }
        final JsonElement value = element.getAsJsonObject().get("$date");
        if (value.isJsonObject()) {
            return new Date(Long.parseLong(value.getAsJsonObject().get("$numberLong").getAsString()));
        }
        return Date.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value.getAsString(), Instant::from));
    }

}
