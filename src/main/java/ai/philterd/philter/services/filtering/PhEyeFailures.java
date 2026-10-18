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

import ai.philterd.phileas.services.filters.ai.pheye.PhEyeUnavailableException;

/**
 * What to say when a policy's PhEye filter cannot get detections from a PhEye service. The message tells the
 * caller what to change and does not name the service, whose address is the deployment's; the log does.
 */
public final class PhEyeFailures {

    private PhEyeFailures() {
    }

    /** The PhEye failure in the exception or its causes, or null when there is none. */
    public static PhEyeUnavailableException find(final Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PhEyeUnavailableException unavailable) {
                return unavailable;
            }
        }
        return null;
    }

    public static String message(final PhEyeUnavailableException ex) {
        return "A PhEye filter in the policy could not get detections from its PhEye service. " + ex.getMessage()
                + " Name a local model in the filter's phEyeConfiguration.modelPath (GET /api/models lists them), "
                + "or an administrator can set PHEYE_ENDPOINT to a running PhEye service.";
    }

}
