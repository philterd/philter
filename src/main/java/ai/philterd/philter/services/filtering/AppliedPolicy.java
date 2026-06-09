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

/**
 * Identifies the policy version that governed a redaction: its name, revision (version), and the
 * SHA-256 content hash of the policy JSON. This is the provenance stamped onto ledger entries and
 * surfaced on the {@code /explain} body and {@code /filter} response headers. It deliberately carries
 * no policy JSON so it is safe to return to callers.
 */
public record AppliedPolicy(String name, int version, String contentHash) {
}
