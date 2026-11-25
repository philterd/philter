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
package ai.philterd.philter.api.filters.content;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * Exposes the first bytes of the request body for format detection, then replays them so the body is
 * still readable in full.
 *
 * <p>Only {@link DocumentType#SIGNATURE_LENGTH} bytes are buffered, so a 10 MB document is not held
 * in memory to check its first eight bytes.
 */
public class ContentTypeVerifyingRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] leadingBytes;
    private InputStream body;

    public ContentTypeVerifyingRequestWrapper(final HttpServletRequest request) throws IOException {

        super(request);

        final InputStream original = request.getInputStream();
        final byte[] peeked = original.readNBytes(DocumentType.SIGNATURE_LENGTH);

        this.leadingBytes = peeked;
        this.body = new SequenceInputStream(new ByteArrayInputStream(peeked), original);

    }

    /**
     * Reads the whole body, which is still served to the rest of the chain. The size limit of the request
     * this wraps applies to the read.
     */
    public byte[] readBody() throws IOException {
        final byte[] all = body.readAllBytes();
        body = new ByteArrayInputStream(all);
        return all;
    }

    /**
     * Reads up to {@code max} bytes from the start of the body without consuming them, so a check can look at the
     * beginning of a large body without holding all of it. The size limit of the request this wraps applies.
     */
    public byte[] peek(final int max) throws IOException {
        final byte[] prefix = body.readNBytes(max);
        body = new SequenceInputStream(new ByteArrayInputStream(prefix), body);
        return prefix;
    }

    /** The leading bytes of the body, up to {@link DocumentType#SIGNATURE_LENGTH}. */
    public byte[] getLeadingBytes() {
        return leadingBytes;
    }

    @Override
    public ServletInputStream getInputStream() {

        return new ServletInputStream() {

            @Override
            public int read() throws IOException {
                return body.read();
            }

            @Override
            public int read(final byte[] b, final int off, final int len) throws IOException {
                return body.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                try {
                    return body.available() == 0;
                } catch (final IOException e) {
                    return true;
                }
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(final ReadListener readListener) {
                throw new UnsupportedOperationException("Asynchronous reads are not supported.");
            }

        };

    }

}
