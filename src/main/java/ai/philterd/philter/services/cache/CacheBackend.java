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

/**
 * The storage operations required by the caches. Implementations may be backed by an external
 * Valkey/Redis server or by an in-process, ephemeral store.
 */
public interface CacheBackend {

    /** Stores a string value with a time-to-live, in seconds. */
    void setex(String key, int ttlSeconds, String value);

    /** Returns the string value for the key, or {@code null} if absent or expired. */
    String get(String key);

    /** Returns whether a (non-expired) value exists for the key. */
    boolean exists(String key);

    /** Removes the key (whether it holds a string or a hash). */
    void del(String key);

    /** Sets a field within the hash stored at the key. */
    void hset(String key, String field, String value);

    /** Returns the field value within the hash at the key, or {@code null} if absent or expired. */
    String hget(String key, String field);

    /** Returns whether the field exists within the (non-expired) hash at the key. */
    boolean hexists(String key, String field);

    /** Sets a time-to-live, in seconds, on the key. */
    void expire(String key, int ttlSeconds);

    /** Releases any resources held by the backend. */
    void close();

}
