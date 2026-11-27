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
package ai.philterd.philter.testutil;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Generated email messages for tests. Every address is on example.com. */
public final class EmailMessages {

    public static final Session SESSION = Session.getInstance(new Properties());

    public static final String FROM = "sender@example.com";
    public static final String TO = "recipient@example.com";

    private EmailMessages() {
    }

    /** A plain text message with a subject, sender, recipient, date, and Message-ID. */
    public static byte[] eml(final String subject, final String body) throws Exception {
        return bytes(message(subject, body));
    }

    /** A single-part text message whose body is sent with the given transfer encoding. */
    public static byte[] emlEncoded(final String encoding, final String body) throws Exception {
        final MimeMessage message = message("Encoded", body);
        message.saveChanges();
        message.setHeader("Content-Transfer-Encoding", encoding);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toByteArray();
    }

    /** A message with a text body and one attachment. */
    public static byte[] emlWithAttachment(final String body, final String fileName, final String type,
                                           final byte[] content) throws Exception {
        final MimeMessage message = message("With an attachment", null);
        message.setContent(new MimeMultipart(text(body), attachment(fileName, type, content)));
        return bytes(message);
    }

    /** A message with an HTML body showing an inline image by its Content-ID, and one attachment. */
    public static byte[] emlWithInlineImageAndAttachment(final String body, final String fileName, final String type,
                                                         final byte[] content) throws Exception {
        final MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>" + body + "</p><img src=\"cid:logo@example.com\">", "UTF-8", "html");
        final MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")));
        image.setContentID("<logo@example.com>");
        image.setDisposition(MimeBodyPart.INLINE);
        image.setHeader("Content-Transfer-Encoding", "base64");
        final MimeBodyPart related = new MimeBodyPart();
        related.setContent(new MimeMultipart("related", html, image));
        final MimeMessage message = message("With an inline image", null);
        message.setContent(new MimeMultipart(related, attachment(fileName, type, content)));
        return bytes(message);
    }

    /** A message with a text body and the given number of small attachments. */
    public static byte[] emlWithAttachments(final int count) throws Exception {
        final MimeMultipart multipart = new MimeMultipart(text("See the attached notes."));
        for (int i = 0; i < count; i++) {
            multipart.addBodyPart(attachment("note-" + i + ".txt", "text/plain", ("Note " + i).getBytes(StandardCharsets.UTF_8)));
        }
        final MimeMessage message = message("Notes", null);
        message.setContent(multipart);
        return bytes(message);
    }

    /** An attachment of a MIME message. {@code type} may carry parameters, such as a charset. */
    public record Attachment(String fileName, String type, byte[] content) {
    }

    /** A message with a text body and the given attachments. */
    public static byte[] emlWithAttachments(final String body, final Attachment... attachments) throws Exception {
        final MimeMultipart multipart = new MimeMultipart(text(body));
        for (final Attachment attachment : attachments) {
            multipart.addBodyPart(attachment(attachment.fileName(), attachment.type(), attachment.content()));
        }
        final MimeMessage message = message("With attachments", null);
        message.setContent(multipart);
        return bytes(message);
    }

    /** A message forwarding another, {@code depth} messages deep. */
    public static byte[] nested(final int depth, final String innermostBody) throws Exception {
        MimeMessage message = message("Innermost", innermostBody);
        for (int i = 0; i < depth; i++) {
            final MimeBodyPart forwarded = new MimeBodyPart();
            forwarded.setContent(message, "message/rfc822");
            final MimeMessage outer = message("Forward " + (i + 1), null);
            outer.setContent(new MimeMultipart(text("Forwarding."), forwarded));
            outer.saveChanges();
            message = outer;
        }
        return bytes(message);
    }

    /** An S/MIME encrypted message, whose body cannot be read. */
    public static byte[] encrypted() throws Exception {
        final MimeMessage message = message("Encrypted", null);
        message.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(
                new byte[]{0x30, (byte) 0x82, 0x01, 0x00, 0x06, 0x09}, "application/pkcs7-mime; smime-type=enveloped-data")));
        message.setHeader("Content-Transfer-Encoding", "base64");
        return bytes(message);
    }

    /** A message whose text body is PGP-encrypted, which only redaction finds. */
    public static byte[] pgpEncrypted() throws Exception {
        return eml("Encrypted", "-----BEGIN PGP MESSAGE-----\n\nhQEMA0xvcmVtIGlwc3VtIGRvbG9y\n=abcd\n-----END PGP MESSAGE-----\n");
    }

    private static MimeMessage message(final String subject, final String body) throws Exception {
        final MimeMessage message = new MimeMessage(SESSION);
        message.setFrom(new InternetAddress(FROM, "Avery Sender"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(TO, "Riley Recipient"));
        message.setSubject(subject);
        message.setSentDate(new java.util.Date(1_700_000_000_000L));
        message.setHeader("X-Mailer", "Example Mailer 1.0");
        if (body != null) {
            message.setText(body, "UTF-8");
        }
        return message;
    }

    private static MimeBodyPart text(final String body) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setText(body, "UTF-8");
        return part;
    }

    private static MimeBodyPart attachment(final String fileName, final String type, final byte[] content) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new jakarta.activation.DataHandler(new jakarta.mail.util.ByteArrayDataSource(content, type)));
        part.setFileName(fileName);
        part.setDisposition(MimeBodyPart.ATTACHMENT);
        // Base64, so the content survives as bytes.
        part.setHeader("Content-Transfer-Encoding", "base64");
        return part;
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        message.saveChanges();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toByteArray();
    }

    /** An attachment of an Outlook message. A null type is left out, as Outlook may. */
    public record MsgAttachment(String name, String type, byte[] data) {
    }

    /** An Outlook message with a subject, sender, one To recipient, a text body, and attachments of the given sizes. */
    public static byte[] msg(final String subject, final String body, final int... attachmentSizes) throws IOException {
        final MsgAttachment[] attachments = new MsgAttachment[attachmentSizes.length];
        for (int i = 0; i < attachmentSizes.length; i++) {
            attachments[i] = new MsgAttachment("file-" + i + ".bin", "application/octet-stream", new byte[attachmentSizes[i]]);
        }
        return msgWithAttachments(subject, body, attachments);
    }

    /** As {@link #msg(String, String, int...)}, with the given attachments. */
    public static byte[] msgWithAttachments(final String subject, final String body, final MsgAttachment... attachments)
            throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final DirectoryEntry root = fs.getRoot();
            string(root, 0x001A, "IPM.Note");
            string(root, 0x0037, subject);
            string(root, 0x0C1A, "Avery Sender");
            string(root, 0x0C1F, FROM);
            string(root, 0x0E04, "Riley Recipient");
            string(root, 0x1000, body);
            // The properties stream header is 32 bytes for a top-level message.
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[32]));

            final DirectoryEntry recipient = root.createDirectory("__recip_version1.0_#00000000");
            string(recipient, 0x3001, "Riley Recipient");
            string(recipient, 0x3003, TO);
            string(recipient, 0x39FE, TO);
            recipient.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[8]));

            for (int i = 0; i < attachments.length; i++) {
                final DirectoryEntry attachment = root.createDirectory(String.format("__attach_version1.0_#%08X", i));
                string(attachment, 0x3707, attachments[i].name());
                string(attachment, 0x370E, attachments[i].type());
                attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(attachments[i].data()));
                attachment.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[8]));
            }

            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    /** A compound file that is not an Outlook message, laid out as a legacy Word document is. */
    public static byte[] legacyWordDocument() throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.getRoot().createDocument("WordDocument", new ByteArrayInputStream(new byte[512]));
            fs.getRoot().createDocument("1Table", new ByteArrayInputStream(new byte[512]));
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void string(final DirectoryEntry dir, final int property, final String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            dir.createDocument(String.format("__substg1.0_%04X001F", property),
                    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
        }
    }

}
