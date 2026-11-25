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
import ai.philterd.phileas.services.email.EmailRedactor;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.api.exceptions.UnsupportedMediaTypeException;
import ai.philterd.philter.utils.EnvUtils;
import jakarta.mail.Header;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks an email body before it is redacted: that it is a message of the declared format, and that it stays within
 * {@code EMAIL_MAX_BYTES}, {@code EMAIL_MAX_ATTACHMENT_BYTES}, {@code EMAIL_MAX_ATTACHMENTS}, and
 * {@code EMAIL_MAX_NESTING_DEPTH}. Attachments are counted in nested messages too, and a nested message counts as
 * one. A message Phileas would refuse as encrypted by its media type is refused here, so a queued one is refused
 * when it is submitted; a PGP-encrypted body is found only when the message is redacted.
 */
public final class EmailInputValidator {

    /** The two email formats Philter accepts. */
    public enum Format {

        EML(MimeType.MESSAGE_RFC822),
        MSG(MimeType.APPLICATION_VND_MS_OUTLOOK);

        private final MimeType mimeType;

        Format(final MimeType mimeType) {
            this.mimeType = mimeType;
        }

        public MimeType mimeType() {
            return mimeType;
        }

    }

    record Limits(long maxBytes, long maxAttachmentBytes, int maxAttachments, int maxNestingDepth) {
    }

    /** What the check found that redaction needs to know. */
    public record Inspection(boolean hasPdfAttachment) {
    }

    static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    static final int DEFAULT_MAX_ATTACHMENTS = 100;
    static final int DEFAULT_MAX_NESTING_DEPTH = 10;

    private static final Limits LIMITS = new Limits(
            EnvUtils.getLong("EMAIL_MAX_BYTES", DEFAULT_MAX_BYTES),
            EnvUtils.getLong("EMAIL_MAX_ATTACHMENT_BYTES", DEFAULT_MAX_ATTACHMENT_BYTES),
            EnvUtils.getInt("EMAIL_MAX_ATTACHMENTS", DEFAULT_MAX_ATTACHMENTS),
            EnvUtils.getInt("EMAIL_MAX_NESTING_DEPTH", DEFAULT_MAX_NESTING_DEPTH));

    // As Phileas reads a MIME message: every header name is printable ASCII other than the colon, and at least one
    // is a standard message header.
    // As Phileas's EmailRedactor refuses them.
    private static final Set<String> ENCRYPTED_TYPES = Set.of("application/pkcs7-mime", "application/x-pkcs7-mime",
            "multipart/encrypted", "application/pgp-encrypted");

    private static final String PDF = "application/pdf";
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private static final Pattern HEADER_NAME = Pattern.compile("[!-9;-~]+");
    private static final Set<String> MESSAGE_HEADERS = Set.of("from", "to", "cc", "bcc", "subject", "date",
            "message-id", "mime-version", "received", "return-path", "sender", "reply-to", "in-reply-to",
            "references", "content-type");

    // An Outlook message's attachments, an attachment's data, and a message embedded as an attachment.
    private static final String MSG_ATTACHMENT = "__attach_version1.0_#";
    private static final String MSG_ATTACHMENT_DATA = "__substg1.0_37010102";
    private static final String MSG_EMBEDDED_MESSAGE = "__substg1.0_3701000D";
    // PR_ATTACH_MIME_TAG, and PR_ATTACH_LONG_FILENAME, PR_ATTACH_FILENAME, and PR_DISPLAY_NAME in the order Phileas
    // takes them, each as a Unicode or 8-bit string.
    private static final int MSG_MIME_TAG = 0x370E;
    private static final int[] MSG_NAMES = {0x3707, 0x3704, 0x3001};

    private static final Session SESSION = session();

    private EmailInputValidator() {
    }

    /** The email format a MIME type declares, or null when it is not email. */
    public static Format declaredFormat(final MimeType mimeType) {
        for (final Format format : Format.values()) {
            if (format.mimeType == mimeType) {
                return format;
            }
        }
        return null;
    }

    /** The email format a {@code Content-Type} header declares, ignoring parameters, or null. */
    public static Format declaredFormat(final String contentType) {
        if (contentType == null) {
            return null;
        }
        final String bare = contentType.split(";", 2)[0].strip();
        for (final Format format : Format.values()) {
            if (format.mimeType.getValue().equalsIgnoreCase(bare)) {
                return format;
            }
        }
        return null;
    }

    public static Inspection validate(final byte[] input, final Format declared) {
        return validate(input, declared, LIMITS);
    }

    /** As {@link #validate(byte[], Format)} with the given limits; 0 or less sets no limit. */
    static Inspection validate(final byte[] input, final Format declared, final Limits limits) {

        if (limits.maxBytes() > 0 && input.length > limits.maxBytes()) {
            throw new PayloadTooLargeException("The email message is larger than " + limits.maxBytes()
                    + " bytes, the most EMAIL_MAX_BYTES allows.");
        }

        final Counter counter = new Counter(limits);

        if (declared == Format.MSG) {
            checkOutlook(input, counter);
        } else {
            checkMime(input, counter);
        }

        return new Inspection(counter.hasPdfAttachment);

    }

    private static void checkOutlook(final byte[] input, final Counter counter) {

        if (!EmailRedactor.isOutlookMessage(input)) {
            throw new UnsupportedMediaTypeException("The request declares " + Format.MSG.mimeType.getValue()
                    + " but the body is not an Outlook message.");
        }

        final DirectoryEntry root;
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(input))) {
            root = fs.getRoot();
            // A legacy Word or Excel file is a compound file too, and only its streams tell it apart.
            if (!isOutlookMessage(root)) {
                throw new UnsupportedMediaTypeException("The request declares " + Format.MSG.mimeType.getValue()
                        + " but the body is a compound file that is not an Outlook message, such as a legacy Word"
                        + " (.doc) or Excel (.xls) file.");
            }
            outlookMessage(root, 0, counter);
        } catch (final IOException | RuntimeException e) {
            if (e instanceof UnsupportedMediaTypeException || e instanceof PayloadTooLargeException
                    || e instanceof BadRequestException) {
                throw (RuntimeException) e;
            }
            throw new BadRequestException("The email message is not a readable Outlook message.");
        }

    }

    private static boolean isOutlookMessage(final DirectoryEntry directory) {
        if (!directory.hasEntry("__properties_version1.0")) {
            return false;
        }
        for (final Entry entry : directory) {
            if (entry.getName().startsWith("__substg1.0_")) {
                return true;
            }
        }
        return false;
    }

    private static void outlookMessage(final DirectoryEntry message, final int depth, final Counter counter) throws IOException {
        for (final Entry entry : message) {
            if (!(entry instanceof DirectoryEntry attachment) || !entry.getName().startsWith(MSG_ATTACHMENT)) {
                continue;
            }
            counter.attachment();
            if (attachment.hasEntry(MSG_EMBEDDED_MESSAGE)
                    && attachment.getEntry(MSG_EMBEDDED_MESSAGE) instanceof DirectoryEntry embedded) {
                counter.nested(depth + 1);
                outlookMessage(embedded, depth + 1, counter);
            } else if (attachment.hasEntry(MSG_ATTACHMENT_DATA)
                    && attachment.getEntry(MSG_ATTACHMENT_DATA) instanceof DocumentEntry data) {
                final String type = msgString(attachment, MSG_MIME_TAG);
                if (type != null && ENCRYPTED_TYPES.contains(type.strip().toLowerCase(Locale.ROOT))) {
                    throw encrypted(type);
                }
                counter.attachmentSize(data.getSize());
                if (!counter.hasPdfAttachment) {
                    counter.hasPdfAttachment = isPdf(type, msgName(attachment), leadingBytes(data));
                }
            }
        }
    }

    // The first name Phileas would take for an attachment, or null.
    private static String msgName(final DirectoryEntry attachment) throws IOException {
        for (final int property : MSG_NAMES) {
            final String name = msgString(attachment, property);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    // A string property, stored as UTF-16LE (type 001F) or as 8-bit text (001E), or null.
    private static String msgString(final DirectoryEntry directory, final int property) throws IOException {
        final String unicode = String.format("__substg1.0_%04X001F", property);
        final String eightBit = String.format("__substg1.0_%04X001E", property);
        if (directory.hasEntry(unicode) && directory.getEntry(unicode) instanceof DocumentEntry entry) {
            return trimNull(new String(read(entry), StandardCharsets.UTF_16LE));
        }
        if (directory.hasEntry(eightBit) && directory.getEntry(eightBit) instanceof DocumentEntry entry) {
            return trimNull(new String(read(entry), StandardCharsets.ISO_8859_1));
        }
        return null;
    }

    private static String trimNull(final String value) {
        final int end = value.indexOf('\0');
        return end < 0 ? value : value.substring(0, end);
    }

    private static byte[] read(final DocumentEntry entry) throws IOException {
        try (DocumentInputStream in = new DocumentInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static byte[] leadingBytes(final DocumentEntry entry) throws IOException {
        try (DocumentInputStream in = new DocumentInputStream(entry)) {
            return in.readNBytes(PDF_SIGNATURE.length);
        }
    }

    private static void checkMime(final byte[] input, final Counter counter) {

        // Phileas reads any compound file as an Outlook message, whatever was declared.
        if (EmailRedactor.isOutlookMessage(input)) {
            throw new UnsupportedMediaTypeException("The request declares " + Format.EML.mimeType.getValue()
                    + " but the body is an Outlook message or another compound file. Send an Outlook message as "
                    + Format.MSG.mimeType.getValue() + ".");
        }

        final MimeMessage message;
        try {
            message = new MimeMessage(SESSION, new ByteArrayInputStream(input));
            requireMessageHeaders(message);
        } catch (final MessagingException e) {
            throw notMime();
        }

        try {
            mimePart(message, 0, 0, counter);
        } catch (final MessagingException | IOException e) {
            throw new BadRequestException("The email message is not a readable MIME message.");
        }

    }

    private static void requireMessageHeaders(final MimeMessage message) throws MessagingException {
        boolean known = false;
        final Enumeration<Header> headers = message.getAllHeaders();
        while (headers.hasMoreElements()) {
            final String name = headers.nextElement().getName();
            if (!HEADER_NAME.matcher(name).matches()) {
                throw notMime();
            }
            known |= MESSAGE_HEADERS.contains(name.toLowerCase(Locale.ROOT));
        }
        if (!known) {
            throw notMime();
        }
    }

    private static UnsupportedMediaTypeException notMime() {
        return new UnsupportedMediaTypeException("The request declares " + Format.EML.mimeType.getValue()
                + " but the body is not a MIME email message: it does not start with a block of message headers.");
    }

    private static void mimePart(final Part part, final int messageDepth, final int partDepth, final Counter counter)
            throws MessagingException, IOException {

        if (partDepth > EmailRedactor.MAX_DEPTH) {
            throw new PayloadTooLargeException("The email message nests parts more than " + EmailRedactor.MAX_DEPTH
                    + " levels deep.");
        }

        final String type = baseType(part);

        if (ENCRYPTED_TYPES.contains(type)) {
            throw encrypted(type);
        }

        if (type.startsWith("multipart/")) {
            if (part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    mimePart(multipart.getBodyPart(i), messageDepth, partDepth + 1, counter);
                }
            }
        } else if ("message/rfc822".equals(type)) {
            counter.attachment();
            counter.nested(messageDepth + 1);
            try (InputStream in = part.getInputStream()) {
                mimePart(new MimeMessage(SESSION, in), messageDepth + 1, partDepth + 1, counter);
            }
        } else if (isAttachment(part)) {
            counter.attachment();
            final byte[] leading = new byte[PDF_SIGNATURE.length];
            counter.attachmentSize(decodedSize(part, counter.limits.maxAttachmentBytes(), leading));
            if (!counter.hasPdfAttachment) {
                counter.hasPdfAttachment = isPdf(type, fileName(part), leading);
            }
        }

    }

    private static BadRequestException encrypted(final String type) {
        return new BadRequestException("The email message's content is encrypted or wrapped (" + type
                + ") and cannot be read, so it was not redacted.");
    }

    // As Phileas recognizes a PDF attachment, by its media type, its filename, or its content. Taking a message
    // for one when Phileas would not only costs a PDF slot.
    private static boolean isPdf(final String type, final String fileName, final byte[] leading) {
        if (type != null && PDF.equalsIgnoreCase(type.strip())) {
            return true;
        }
        if (fileName != null && fileName.strip().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return true;
        }
        return leading != null && java.util.Arrays.equals(leading, PDF_SIGNATURE);
    }

    private static String fileName(final Part part) {
        try {
            final String fileName = part.getFileName();
            return fileName == null ? null : MimeUtility.decodeText(fileName);
        } catch (final MessagingException | IOException e) {
            return null;
        }
    }

    private static String baseType(final Part part) {
        try {
            final String header = part.getContentType();
            return header == null ? "text/plain" : new ContentType(header).getBaseType().toLowerCase(Locale.ROOT);
        } catch (final MessagingException e) {
            return "application/octet-stream";
        }
    }

    private static boolean isAttachment(final Part part) {
        try {
            return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
        } catch (final MessagingException e) {
            return false;
        }
    }

    /*
     * Reads no further than one byte past the limit, or with no limit only the leading bytes, which it copies to
     * {@code leading}. An encoding that cannot be decoded is measured as sent.
     */
    private static long decodedSize(final Part part, final long limit, final byte[] leading) throws MessagingException {
        try (InputStream in = part.getInputStream()) {
            final int first = in.readNBytes(leading, 0, leading.length);
            if (limit <= 0) {
                return 0;
            }
            final byte[] buffer = new byte[8192];
            long total = first;
            int read;
            while (total <= limit && (read = in.read(buffer)) != -1) {
                total += read;
            }
            return total;
        } catch (final IOException e) {
            return Math.max(part.getSize(), 0);
        }
    }

    private static Session session() {
        final Properties properties = new Properties();
        properties.setProperty("mail.mime.allowutf8", "true");
        properties.setProperty("mail.mime.address.strict", "false");
        return Session.getInstance(properties);
    }

    private static final class Counter {

        private final Limits limits;
        private int attachments;
        private boolean hasPdfAttachment;

        Counter(final Limits limits) {
            this.limits = limits;
        }

        void attachment() {
            attachments++;
            if (limits.maxAttachments() > 0 && attachments > limits.maxAttachments()) {
                throw new PayloadTooLargeException("The email message has more than " + limits.maxAttachments()
                        + " attachments, the most EMAIL_MAX_ATTACHMENTS allows.");
            }
        }

        void attachmentSize(final long size) {
            if (limits.maxAttachmentBytes() > 0 && size > limits.maxAttachmentBytes()) {
                throw new PayloadTooLargeException("The email message has an attachment larger than "
                        + limits.maxAttachmentBytes() + " bytes, the most EMAIL_MAX_ATTACHMENT_BYTES allows.");
            }
        }

        void nested(final int depth) {
            if (limits.maxNestingDepth() > 0 && depth > limits.maxNestingDepth()) {
                throw new PayloadTooLargeException("The email message nests messages more than "
                        + limits.maxNestingDepth() + " deep, the most EMAIL_MAX_NESTING_DEPTH allows.");
            }
            if (depth > EmailRedactor.MAX_DEPTH) {
                throw new PayloadTooLargeException("The email message nests messages more than "
                        + EmailRedactor.MAX_DEPTH + " deep.");
            }
        }

    }

}
