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
package ai.philterd.philter.model;

public enum ContentType {

    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"),
    PDF("application/pdf", ".pdf"),
    TXT("text/plain", ".txt");

    private final String contentType;
    private final String extension;

    ContentType(final String contentType, final String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public static ContentType fromString(final String contentType) {

        for (final ContentType ct : ContentType.values()) {

            if (ct.contentType.equals(contentType)) {
                return ct;
            }

        }

        return null;
    }

    public static boolean isValidContentType(final String contentType) {
        return fromString(contentType) != null;
    }

    public String getExtension() {
        return extension;
    }

    @Override
    public String toString() {
        return contentType;
    }

    public String getContentType() {
        return contentType;
    }

}
