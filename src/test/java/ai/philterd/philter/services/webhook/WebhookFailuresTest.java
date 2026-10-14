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

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebhookFailuresTest {

    @Test
    @DisplayName("Philter's own delivery messages are shown as they are")
    void philtersOwnMessagesPassThrough() {
        assertEquals("Webhook responded with HTTP 500",
                WebhookFailures.describe(new WebhookService.WebhookDeliveryException("Webhook responded with HTTP 500")));
        assertEquals("Webhook delivery to hooks.example.com resolved only to addresses that are not permitted.",
                WebhookFailures.describe(new WebhookDestinationRefusedException(
                        "Webhook delivery to hooks.example.com resolved only to addresses that are not permitted.")));
    }

    @Test
    @DisplayName("A lookup failure is reported without the resolver's message")
    void aLookupFailureIsReducedToItsKind() {
        assertEquals(WebhookFailures.UNRESOLVED,
                WebhookFailures.describe(new UnknownHostException("hooks.internal.corp: nodename nor servname provided")));
    }

    @Test
    @DisplayName("A refused connection is reported without the address and port the client tried")
    void aRefusedConnectionIsReducedToItsKind() {
        assertEquals(WebhookFailures.NOT_CONNECTED, WebhookFailures.describe(
                new HttpHostConnectException("Connect to http://hooks.example.com:8443 [/10.0.0.5] failed: Connection refused")));
        assertEquals(WebhookFailures.NOT_CONNECTED, WebhookFailures.describe(new NoRouteToHostException("No route to host")));
    }

    @Test
    @DisplayName("Timeouts, TLS failures and missing responses are each reduced to their kind")
    void otherNetworkFailuresAreReducedToTheirKind() {
        assertEquals(WebhookFailures.TIMED_OUT, WebhookFailures.describe(new SocketTimeoutException("Read timed out")));
        assertEquals(WebhookFailures.TIMED_OUT, WebhookFailures.describe(new ConnectTimeoutException("Connect to 10.0.0.5:443 timed out")));
        assertEquals(WebhookFailures.TLS_FAILED, WebhookFailures.describe(new SSLHandshakeException("PKIX path building failed")));
        assertEquals(WebhookFailures.NO_RESPONSE, WebhookFailures.describe(new NoHttpResponseException("10.0.0.5:443 failed to respond")));
    }

    @Test
    @DisplayName("A failure's cause is described when the failure itself is a wrapper")
    void aWrappedFailureIsDescribedByItsCause() {
        assertEquals(WebhookFailures.TLS_FAILED,
                WebhookFailures.describe(new RuntimeException("wrapped", new SSLHandshakeException("bad certificate"))));
    }

    @Test
    @DisplayName("Anything else is reported generically")
    void anythingElseIsGeneric() {
        assertEquals(WebhookFailures.GENERIC, WebhookFailures.describe(new IOException("Unexpected end of stream on 10.0.0.5")));
        assertEquals(WebhookFailures.GENERIC, WebhookFailures.describe(new IllegalStateException("internal detail")));
    }

}
