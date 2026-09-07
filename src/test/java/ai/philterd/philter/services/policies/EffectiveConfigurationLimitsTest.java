package ai.philterd.philter.services.policies;

import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectiveConfigurationLimitsTest {
    @Test void limitUsesUtf8BytesAndIncludesTheBoundary() {
        String boundary = "é".repeat(EffectiveConfigurationLimits.MAX_BYTES / 2);
        assertDoesNotThrow(() -> EffectiveConfigurationLimits.requireSize(boundary));
        assertThrows(PayloadTooLargeException.class,
                () -> EffectiveConfigurationLimits.requireSize(boundary + "a"));
    }
}
