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
package ai.philterd.philter.audit;

import ai.philterd.philter.api.controllers.AbstractApiController;
import ai.philterd.philter.utils.IpAddresses;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The client address an audit event records: that of the request being served, or, on a thread doing work
 * a request submitted, that request's. Resolved as {@link AbstractApiController#getClientIpAddress} resolves
 * it, so a trusted proxy's {@code X-Forwarded-For} is honored the same way everywhere.
 */
public final class ClientAddress {

    private static final ThreadLocal<String> SUBMITTED_BY = new ThreadLocal<>();

    private ClientAddress() {
    }

    /** The current client address, or null when no request is behind the work on this thread. */
    public static String current() {
        final RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return AbstractApiController.getClientIpAddress(servletAttributes.getRequest());
        }
        return SUBMITTED_BY.get();
    }

    /** Records, until {@link #clear()}, the address of the request that submitted this thread's work. */
    public static void setSubmittedBy(final String clientAddress) {
        SUBMITTED_BY.set(isAddress(clientAddress) ? clientAddress : null);
    }

    public static void clear() {
        SUBMITTED_BY.remove();
    }

    /** Whether the value is an IP address literal. */
    public static boolean isAddress(final String value) {
        return IpAddresses.parseLiteral(value) != null;
    }

}
