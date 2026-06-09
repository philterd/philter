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
 * A policy version pinned at request-acceptance time and carried to deferred (asynchronous) redaction
 * so the worker redacts with, and stamps, exactly the version that was in force when the request was
 * accepted, rather than whatever is current when the job later runs. Unlike {@link AppliedPolicy},
 * this carries the policy JSON so the worker can redact with the pinned content.
 */
public record PinnedPolicy(String name, int version, String contentHash, String policyJson) {
}
