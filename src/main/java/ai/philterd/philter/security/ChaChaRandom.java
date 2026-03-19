package ai.philterd.philter.security;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Random;

public class ChaChaRandom extends Random {

    private final byte[] key = new byte[32];
    private final byte[] nonce = new byte[12];

    private byte[] buffer = new byte[64]; // One ChaCha block
    private int bufferPtr = buffer.length;
    private int counter = 0;

    public ChaChaRandom() {

        // Seed with SecureRandom to ensure high entropy for the key/nonce
        SecureRandom sr = new SecureRandom(); // Comes from AWS Nitro
        sr.nextBytes(key);
        sr.nextBytes(nonce);

    }

    /**
     * Overriding next(int bits) is the "hook" for all Random methods.
     */
    @Override
    protected int next(int bits) {

        final int bytesNeeded = (bits + 7) / 8;
        int result = 0;

        for (int i = 0; i < bytesNeeded; i++) {
            if (bufferPtr >= buffer.length) {
                refreshBuffer();
            }
            result = (result << 8) | (buffer[bufferPtr++] & 0xFF);
        }

        return result >>> ((bytesNeeded * 8) - bits);
    }

    private void refreshBuffer() {

        try {

            final ChaCha20ParameterSpec paramSpec = new ChaCha20ParameterSpec(nonce, counter);

            final Cipher cipher = Cipher.getInstance("ChaCha20");
            final SecretKeySpec keySpec = new SecretKeySpec(key, "ChaCha20");

            // The counter ensures each block is unique
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec);

            // We "encrypt" an empty array to get the raw keystream
            buffer = cipher.update(new byte[64]);
            bufferPtr = 0;
            counter++;

            // Update nonce or counter manually if needed for very long streams

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ChaCha keystream", e);
        }

    }

}