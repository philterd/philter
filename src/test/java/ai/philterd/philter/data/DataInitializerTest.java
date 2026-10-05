package ai.philterd.philter.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataInitializerTest {
    @Test void firstStartupRequiresAPrivateBootstrapApiKey() {
        for (String invalid : new String[]{null, "", "admin", "sk_short", "pk_" + "a".repeat(32), "sk_" + "a".repeat(31) + "-"}) {
            assertThrows(IllegalStateException.class, () -> DataInitializer.requireBootstrapApiKey(invalid));
        }
        final String key = "sk_" + "a1".repeat(16);
        assertEquals(key, DataInitializer.requireBootstrapApiKey(key));
    }
}
