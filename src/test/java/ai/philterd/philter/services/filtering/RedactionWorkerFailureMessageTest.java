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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionWorkerFailureMessageTest {

    private static final PhEyeUnavailableException UNREACHABLE = new PhEyeUnavailableException(
            "The PhEye service could not be reached.", "http://philter-ph-eye-1:5000/",
            new UnknownHostException("philter-ph-eye-1"));

    @Test
    void aPhEyeFailureSaysWhatToChangeWithoutTheHost() {
        final String message = RedactionWorker.failureMessage(UNREACHABLE);
        assertTrue(message.contains("could not get detections from its PhEye service"), message);
        assertTrue(message.contains("PHEYE_ENDPOINT"), message);
        assertFalse(message.contains("philter-ph-eye-1"), message);
    }

    @Test
    void aWrappedPhEyeFailureIsFoundToo() {
        assertEquals(PhEyeFailures.message(UNREACHABLE), RedactionWorker.failureMessage(new Exception("filtering", UNREACHABLE)));
    }

    @Test
    void anyOtherFailureIsStillGeneric() {
        assertEquals(RedactionWorker.failureMessage(new IOException("internal detail")),
                RedactionWorker.failureMessage(new IllegalStateException("other")));
        assertFalse(RedactionWorker.failureMessage(new IOException("internal detail")).contains("internal detail"));
    }

}
