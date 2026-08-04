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

/**
 * A document format recognized from the leading bytes of a request body.
 *
 * <p>Philter accepts only PDF and plain text. The other formats are listed so a body that is plainly
 * something else can be named in the rejection, rather than falling through to {@link #UNKNOWN} and
 * being treated as text. Text has no signature of its own, so it can only ever be inferred from the
 * absence of a known one, which is why the declared content type is still required.
 */
public enum DocumentType {

    PDF("PDF", new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}),

    /** ZIP container. Also what a .docx, .xlsx, or .pptx is underneath. */
    ZIP("ZIP or Office Open XML (.docx, .xlsx, .pptx)", new byte[]{0x50, 0x4B, 0x03, 0x04}),

    /** Legacy Microsoft Office compound file: .doc, .xls, .ppt. */
    OLE2("legacy Microsoft Office (.doc, .xls, .ppt)",
            new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}),

    JPEG("JPEG image", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),

    PNG("PNG image", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),

    GIF("GIF image", new byte[]{0x47, 0x49, 0x46, 0x38}),

    /** No recognized signature. Consistent with text, but not proof of it. */
    UNKNOWN("unrecognized", new byte[0]);

    /** Bytes needed to recognize the longest signature. */
    public static final int SIGNATURE_LENGTH = 8;

    private final String description;
    private final byte[] signature;

    DocumentType(final String description, final byte[] signature) {
        this.description = description;
        this.signature = signature;
    }

    public String getDescription() {
        return description;
    }

    /** Recognizes the format from the leading bytes, or {@link #UNKNOWN} if none matches. */
    public static DocumentType detect(final byte[] leadingBytes) {

        if (leadingBytes == null) {
            return UNKNOWN;
        }

        for (final DocumentType candidate : values()) {
            if (candidate != UNKNOWN && candidate.matches(leadingBytes)) {
                return candidate;
            }
        }

        return UNKNOWN;

    }

    private boolean matches(final byte[] leadingBytes) {

        if (leadingBytes.length < signature.length) {
            return false;
        }

        for (int i = 0; i < signature.length; i++) {
            if (leadingBytes[i] != signature[i]) {
                return false;
            }
        }

        return true;

    }

}
