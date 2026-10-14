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
package ai.philterd.philter.services.webhook;

import org.apache.hc.core5.http.NoHttpResponseException;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * Describes why a webhook could not be delivered, for the delivery list and the test result. The HTTP client's
 * own messages name resolved addresses, ports, and TLS internals, so only Philter's messages are shown as they
 * are and everything else is reduced to its kind. The full exception is logged.
 */
public final class WebhookFailures {

    static final String UNRESOLVED = "The webhook host could not be resolved.";
    static final String TIMED_OUT = "The webhook URL did not respond in time.";
    static final String NOT_CONNECTED = "Could not connect to the webhook URL.";
    static final String TLS_FAILED = "The TLS connection to the webhook URL failed.";
    static final String NO_RESPONSE = "The webhook URL closed the connection without a response.";
    static final String GENERIC = "The webhook could not be delivered.";

    private WebhookFailures() {
    }

    public static String describe(final Throwable failure) {

        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof WebhookService.WebhookDeliveryException || t instanceof WebhookDestinationRefusedException) {
                return t.getMessage();
            }
            if (t instanceof UnknownHostException) return UNRESOLVED;
            if (t instanceof InterruptedIOException) return TIMED_OUT;
            if (t instanceof SSLException) return TLS_FAILED;
            if (t instanceof NoHttpResponseException) return NO_RESPONSE;
            if (t instanceof ConnectException || t instanceof SocketException) return NOT_CONNECTED;
            if (t.getCause() == t) break;
        }

        return GENERIC;

    }

}
