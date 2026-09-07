package ai.philterd.philter.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataInitializerTest {
    @Test void firstStartupRequiresAPrivateOperatorCredential() {
        for (String insecure : new String[]{null, "", "admin", "short password", "x".repeat(73)}) {
            assertThrows(IllegalStateException.class, () -> DataInitializer.requireBootstrapPassword(insecure));
        }
        assertEquals("a private bootstrap passphrase", DataInitializer.requireBootstrapPassword("a private bootstrap passphrase"));
    }
}
