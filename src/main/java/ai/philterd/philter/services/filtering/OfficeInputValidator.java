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

import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.services.ooxml.OoxmlDocumentType;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.utils.EnvUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Checks a Word or Excel body before it is redacted: that it is a Word document or Excel workbook of the
 * declared type, and that its package stays within {@code OFFICE_MAX_UNCOMPRESSED_BYTES} and
 * {@code OFFICE_MAX_ENTRIES}. Redaction holds every entry of the package in memory, so a small request
 * could otherwise expand to exhaust it.
 */
public final class OfficeInputValidator {

    static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 100L * 1024 * 1024;
    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private static final long MAX_UNCOMPRESSED_BYTES =
            EnvUtils.getLong("OFFICE_MAX_UNCOMPRESSED_BYTES", DEFAULT_MAX_UNCOMPRESSED_BYTES);
    private static final int MAX_ENTRIES = EnvUtils.getInt("OFFICE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES);

    private OfficeInputValidator() {
    }

    /** The Office type a MIME type declares, or null when it is not Word or Excel. */
    public static OoxmlDocumentType declaredType(final MimeType mimeType) {
        for (final OoxmlDocumentType type : OoxmlDocumentType.values()) {
            if (type.mimeType() == mimeType) {
                return type;
            }
        }
        return null;
    }

    /** The Office type a {@code Content-Type} header declares, ignoring parameters, or null. */
    public static OoxmlDocumentType declaredType(final String contentType) {
        if (contentType == null) {
            return null;
        }
        final String bare = contentType.split(";", 2)[0].strip();
        for (final OoxmlDocumentType type : OoxmlDocumentType.values()) {
            if (type.mimeType().getValue().equalsIgnoreCase(bare)) {
                return type;
            }
        }
        return null;
    }

    /** The decompressed size limit, 0 or less for none. */
    public static long maxUncompressedBytes() {
        return MAX_UNCOMPRESSED_BYTES;
    }

    public static void validate(final byte[] input, final OoxmlDocumentType declared) {
        validate(input, declared, MAX_UNCOMPRESSED_BYTES, MAX_ENTRIES);
    }

    /** As {@link #validate(byte[], OoxmlDocumentType)} with the given limits; 0 or less sets no limit. */
    static void validate(final byte[] input, final OoxmlDocumentType declared, final long maxUncompressedBytes,
                         final int maxEntries) {

        // The limits are checked first, since identifying the type reads the package.
        checkExpansion(input, maxUncompressedBytes, maxEntries);

        final OoxmlDocumentType actual = OoxmlDocumentType.of(input);
        if (actual == null) {
            refuseZipBomb(input);
        }
        if (actual != declared) {
            throw new UnsupportedMediaTypeException("The request declares " + declared.mimeType().getValue()
                    + " but the body is " + describe(actual) + ".");
        }

    }

    // Reads the entries as redaction does, a stream of local entries, counting what they decompress to.
    private static void checkExpansion(final byte[] input, final long maxUncompressedBytes, final int maxEntries) {

        final byte[] buffer = new byte[8192];
        long total = 0;
        int entries = 0;

        try (ZipArchiveInputStream zip = new ZipArchiveInputStream(new ByteArrayInputStream(input), "UTF-8", true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (maxEntries > 0 && entries > maxEntries) {
                    throw new PayloadTooLargeException("The document has more than " + maxEntries
                            + " entries, the most OFFICE_MAX_ENTRIES allows.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (maxUncompressedBytes > 0 && total > maxUncompressedBytes) {
                        throw new PayloadTooLargeException("The document decompresses to more than "
                                + maxUncompressedBytes + " bytes, the most OFFICE_MAX_UNCOMPRESSED_BYTES allows.");
                    }
                }
            }
        } catch (final IOException | IllegalArgumentException e) {
            throw new BadRequestException("The document is not a readable Word or Excel document.");
        }

        if (entries == 0) {
            throw new BadRequestException("The document is not a readable Word or Excel document.");
        }

    }

    // OoxmlDocumentType.of reports a package POI refuses as a zip bomb as not Office, which would tell the caller
    // the wrong thing. POI refuses an entry over its grace size (100 KB) that expands to more than
    // 1 / ZipSecureFile.getMinInflateRatio() times its compressed size, and says so only in the message of a plain
    // IOException. Its refusal of an entry over the maximum entry size starts the same way but names no ratio; that
    // limit is OFFICE_MAX_UNCOMPRESSED_BYTES, which checkExpansion has already enforced.
    private static void refuseZipBomb(final byte[] input) {
        try {
            OPCPackage.open(new ByteArrayInputStream(input)).revert();
        } catch (final Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                final String message = cause.getMessage();
                if (message != null && message.startsWith("Zip bomb detected") && message.contains("ratio")) {
                    throw new PayloadTooLargeException("The document has an entry that expands to more than "
                            + Math.round(1 / ZipSecureFile.getMinInflateRatio()) + " times its compressed size, "
                            + "which Philter refuses as a possible zip bomb.");
                }
            }
        }
    }

    private static String describe(final OoxmlDocumentType type) {
        if (type == OoxmlDocumentType.DOCX) {
            return "a Word document";
        }
        if (type == OoxmlDocumentType.XLSX) {
            return "an Excel workbook";
        }
        return "a ZIP file that is not a Word document or Excel workbook (templates, macro-enabled files, "
                + "and PowerPoint presentations are not supported)";
    }

}
