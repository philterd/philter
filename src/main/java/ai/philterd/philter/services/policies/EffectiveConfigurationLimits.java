package ai.philterd.philter.services.policies;

import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import java.nio.charset.StandardCharsets;

/** Bounds resolved execution inputs, independently of the uploaded policy's size. */
public final class EffectiveConfigurationLimits {
    public static final int MAX_BYTES = 1024 * 1024;

    private EffectiveConfigurationLimits() { }

    public static void requireSize(final String json) {
        if (json != null && (json.length() > MAX_BYTES
                || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)) {
            throw exceeded();
        }
    }

    public static PayloadTooLargeException exceeded() {
        return new PayloadTooLargeException("Resolved configuration exceeds the 1 MiB limit.");
    }
}
