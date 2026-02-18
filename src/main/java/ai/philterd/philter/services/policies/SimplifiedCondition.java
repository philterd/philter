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
package ai.philterd.philter.services.policies;

public class SimplifiedCondition {

    private static final int DEFAULT_THRESHOLD = 85;

    private int confidence;

    public SimplifiedCondition() {
        this.confidence = DEFAULT_THRESHOLD;
    }

    public SimplifiedCondition(final int confidence) {
        this.confidence = confidence;
    }

    public double getConfidenceAsDouble() {
        return confidence / 100.0;
    }

    public int getConfidence() {
        return confidence;
    }

    public void setConfidence(int confidence) {
        this.confidence = confidence;
    }

}
