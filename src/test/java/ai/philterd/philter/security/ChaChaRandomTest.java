package ai.philterd.philter.security;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ChaChaRandomTest {

    // TODO: Enable this test.
    @Disabled
    @Test
    public void fairness() {

        // The goal is to compare the Observed frequencies of numbers against the Expected frequencies.
        // If the difference is within a certain statistical threshold, the RNG is considered fair.
        // The formula for the Chi-Squared statistic is:$$\chi^2 = \sum \frac{(O_i - E_i)^2}{E_i}$$
        // Where:
        // $O_i$ is the observed count for a specific value.
        // $E_i$ is the expected count (Total samples / Total possible values).

        int samples = 1_000_000;
        int numBuckets = 10;
        int expected = samples / numBuckets;
        int[] counts = new int[numBuckets];
        final ChaChaRandom rng = new ChaChaRandom();

        // 1. Collect Data
        for (int i = 0; i < samples; i++) {
            counts[rng.nextInt(numBuckets)]++;
        }

        // 2. Calculate Chi-Squared Statistic
        double chiSquare = 0;
        System.out.println("Value | Observed | Expected | Diff");
        System.out.println("------------------------------------");
        for (int i = 0; i < numBuckets; i++) {
            double diff = counts[i] - expected;
            chiSquare += (diff * diff) / expected;
            System.out.printf("%5d | %8d | %8d | %8.2f\n", i, counts[i], expected, diff);
        }

        System.out.println("------------------------------------");
        System.out.printf("Total Chi-Squared Statistic: %.4f\n", chiSquare);

        // For 9 degrees of freedom (10-1), a value < 16.92 is "fair" at 95% confidence
        if (chiSquare < 16.92) {

            System.out.println("Result: The RNG appears to be FAIR.");

        } else {

            System.out.println("Result: The RNG appears to be BIASED.");
            fail("RNG appears to be biased.");

        }

    }

    @Test
    public void serial() {

        // To test if the numbers are independent, we use a Serial Test. While the Frequency Test checks if each number
        // appears enough, the Serial Test checks if pairs of numbers appear in a random order.If an RNG always follows
        // a $5$ with a $3$, it would pass a frequency test but fail a serial test because it's predictable.

        // We create a 2D "transition matrix." If we are testing numbers $0-9$, we create a $10 \times 10$ grid.
        // Every time we generate a pair of numbers $(n_1, n_2)$, we increment the cell at matrix[n1][n2]. In a
        // fair RNG, every cell in that $100$-cell grid should have roughly the same count.

        int samples = 1_000_000;
        int side = 10; // testing pairs of 0-9
        int[][] matrix = new int[side][side];
        ChaChaRandom rng = new ChaChaRandom();

        // 1. Collect pairs
        int prev = rng.nextInt(side);
        for (int i = 0; i < samples; i++) {
            int current = rng.nextInt(side);
            matrix[prev][current]++;
            prev = current;
        }

        // 2. Statistical Analysis
        double expectedPerCell = (double) samples / (side * side);
        double chiSquare = 0;

        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                double diff = matrix[i][j] - expectedPerCell;
                chiSquare += (diff * diff) / expectedPerCell;
            }
        }

        // Degrees of freedom = (side^2 - 1) = 99
        // For 99 degrees of freedom, critical value at 0.05 is ~123.2
        System.out.printf("Serial Chi-Squared Statistic: %.4f\n", chiSquare);

        if (chiSquare < 123.2) {
            System.out.println("Result: No predictable patterns detected (Success).");
        } else {
            System.out.println("Result: Sequential patterns detected (Failure).");
            fail("Serial patterns detected.");
        }

    }

    @Test
    void testRangeLogic() throws Exception {

        // We use our class, but we could pass a "Fixed" seed if we added
        // a constructor for testing.
        ChaChaRandom rng = new ChaChaRandom();

        // Test: Does nextInt(min, max) stay within bounds?
        for (int i = 0; i < 1000; i++) {
            int val = rng.nextInt(1, 10);
            assertTrue(val >= 1 && val < 10, "Value out of bounds: " + val);
        }

    }

    @Test
    void testIndependence() throws Exception {

        ChaChaRandom rng1 = new ChaChaRandom();
        ChaChaRandom rng2 = new ChaChaRandom();

        // Highly unlikely two hardware-seeded engines produce the same first 8 bytes
        assertNotEquals(rng1.nextLong(), rng2.nextLong(), "Engines produced identical output!");

    }


}
