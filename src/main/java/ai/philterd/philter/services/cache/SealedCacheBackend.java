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
package ai.philterd.philter.services.cache;

import ai.philterd.philter.services.encryption.LocalKeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Encrypts and authenticates what Philter keeps in Valkey. Anyone who could write to an unprotected cache
 * could otherwise store an API key record of their choosing and be treated as its user, or change the
 * replacements written into redacted documents; anyone who could read it would see user IDs, scopes,
 * context names and replacements.
 *
 * <p>Each value is sealed with AES-256-GCM, bound to the key and field it is stored under, so a value
 * cannot be moved to another key. A value stored with a time to live carries its expiry, so a copy written
 * back after it expired is refused. Key and field names are replaced by keyed hashes. Both keys are derived
 * from {@code PHILTER_ENCRYPTION_KEY}, which every instance sharing a cache already shares. A value that
 * cannot be opened reads as absent, so the caller goes to the database.
 *
 * <p>Not covered: the sign-in counters (requests per address, and failures for usernames with no account),
 * which Valkey increments itself and so cannot be sealed, so changing one only weakens those limits; deleting
 * entries, which only costs a database read; and writing back a context replacement that was removed, which
 * is accepted until the expiry sealed inside it, an hour after it was cached.
 */
public final class SealedCacheBackend implements CacheBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(SealedCacheBackend.class);

    private static final String NAME_LABEL = "philter:cache-names";
    private static final String VALUE_LABEL = "philter:cache-values";
    private static final String NAME_PREFIX = "philter:";
    private static final String VERSION = "s1.";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final long NO_EXPIRY = 0L;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    // Warned once per process, since a changed PHILTER_ENCRYPTION_KEY makes every entry unreadable at once, and
    // redaction builds a cache, and so one of these, for each request.
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private final CacheBackend delegate;
    private final byte[] nameKey;
    private final SecretKeySpec valueKey;
    private final LongSupplier clock;

    public SealedCacheBackend(final CacheBackend delegate, final byte[] masterKey, final LongSupplier clock) {
        this.delegate = delegate;
        this.nameKey = hmac(masterKey, NAME_LABEL.getBytes(StandardCharsets.UTF_8));
        this.valueKey = new SecretKeySpec(hmac(masterKey, VALUE_LABEL.getBytes(StandardCharsets.UTF_8)), "AES");
        this.clock = clock;
    }

    /** Sealed under keys derived from {@code PHILTER_ENCRYPTION_KEY}. */
    public static SealedCacheBackend fromEnvironment(final CacheBackend delegate) {
        final String configured = System.getenv(LocalKeyProvider.ENCRYPTION_KEY_ENV);
        if (configured == null || configured.isBlank()) {
            // Philter will not start without the key, so this is reachable only outside a running application.
            // A per-JVM key keeps such a caller self-consistent; its entries are unreadable to any other process.
            LOGGER.warn("{} is not set, so the Valkey cache is encrypted under a key that lasts only as long as this "
                    + "JVM.", LocalKeyProvider.ENCRYPTION_KEY_ENV);
            final byte[] ephemeral = new byte[32];
            SECURE_RANDOM.nextBytes(ephemeral);
            return new SealedCacheBackend(delegate, ephemeral, System::currentTimeMillis);
        }
        return new SealedCacheBackend(delegate, Base64.getDecoder().decode(configured.trim()), System::currentTimeMillis);
    }

    @Override
    public long incrementInWindow(final String key, final int windowSeconds) {
        return delegate.incrementInWindow(name(key), windowSeconds);
    }

    @Override
    public void setex(final String key, final int ttlSeconds, final String value) {
        final String name = name(key);
        delegate.setex(name, ttlSeconds, seal(name, "", clock.getAsLong() + ttlSeconds * 1000L, value));
    }

    @Override
    public String get(final String key) {
        final String name = name(key);
        return open(name, "", delegate.get(name));
    }

    @Override
    public boolean exists(final String key) {
        // Only a value that opens counts, so a forged or expired entry is not treated as present.
        return get(key) != null;
    }

    @Override
    public void del(final String key) {
        delegate.del(name(key));
    }

    @Override
    public void hset(final String key, final String field, final String value) {
        final String name = name(key);
        final String sealedField = field(field);
        delegate.hset(name, sealedField, seal(name, sealedField, NO_EXPIRY, value));
    }

    @Override
    public String hget(final String key, final String field) {
        final String name = name(key);
        final String sealedField = field(field);
        return open(name, sealedField, delegate.hget(name, sealedField));
    }

    @Override
    public boolean hexists(final String key, final String field) {
        return hget(key, field) != null;
    }

    @Override
    public void hdel(final String key, final String field) {
        delegate.hdel(name(key), field(field));
    }

    @Override
    public void expire(final String key, final int ttlSeconds) {
        delegate.expire(name(key), ttlSeconds);
    }

    @Override
    public void close() {
        delegate.close();
    }

    String name(final String key) {
        return NAME_PREFIX + HEX.formatHex(hmac(nameKey, ("key:" + key).getBytes(StandardCharsets.UTF_8)));
    }

    String field(final String field) {
        return HEX.formatHex(hmac(nameKey, ("field:" + field).getBytes(StandardCharsets.UTF_8)));
    }

    private String seal(final String name, final String field, final long notAfterMillis, final String value) {
        try {
            final byte[] nonce = new byte[NONCE_BYTES];
            SECURE_RANDOM.nextBytes(nonce);
            final Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, valueKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData(name, field));
            final byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
            final byte[] sealed = cipher.doFinal(ByteBuffer.allocate(Long.BYTES + plaintext.length)
                    .putLong(notAfterMillis).put(plaintext).array());
            return VERSION + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array());
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to encrypt a cache entry.", ex);
        }
    }

    private String open(final String name, final String field, final String stored) {

        if (stored == null) {
            return null;
        }

        final byte[] plaintext;
        try {
            if (!stored.startsWith(VERSION)) {
                throw new IllegalArgumentException("not sealed");
            }
            final byte[] raw = Base64.getDecoder().decode(stored.substring(VERSION.length()));
            final Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, valueKey, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            cipher.updateAAD(associatedData(name, field));
            plaintext = cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES);
        } catch (final Exception ex) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn("A cache entry could not be decrypted, so it was ignored and the database was used instead. "
                        + "This is expected once after {} changes; otherwise something other than Philter wrote to the cache.",
                        LocalKeyProvider.ENCRYPTION_KEY_ENV);
            } else {
                LOGGER.debug("Ignored a cache entry that could not be decrypted.");
            }
            return null;
        }

        final ByteBuffer buffer = ByteBuffer.wrap(plaintext);
        final long notAfter = buffer.getLong();
        if (notAfter != NO_EXPIRY && clock.getAsLong() >= notAfter) {
            return null;
        }
        return new String(plaintext, Long.BYTES, plaintext.length - Long.BYTES, StandardCharsets.UTF_8);

    }

    private static byte[] associatedData(final String name, final String field) {
        return (name + "\u0000" + field).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(final byte[] key, final byte[] data) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to derive a cache name.", ex);
        }
    }

}
