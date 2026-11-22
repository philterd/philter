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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Minimal Word and Excel packages for tests, built in memory. */
public final class OfficeDocuments {

    private static final String CT_RELS = "application/vnd.openxmlformats-package.relationships+xml";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private OfficeDocuments() {
    }

    /** A Word document with one paragraph of {@code text}. */
    public static byte[] docx(final String text) {
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "word/document.xml")));
        entries.put("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p></w:body></w:document>");
        return zip(entries);
    }

    /** An Excel workbook with one sheet whose cell A1 holds {@code text} as a shared string. */
    public static byte[] xlsx(final String text) {
        final String s = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")
                        + override("/xl/worksheets/sheet1.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")
                        + override("/xl/sharedStrings.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml")));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "xl/workbook.xml")));
        entries.put("xl/workbook.xml", "<workbook xmlns=\"" + s + "\" xmlns:r=\"" + REL + "\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        entries.put("xl/_rels/workbook.xml.rels", rels(rel("rId1", REL + "/worksheet", "worksheets/sheet1.xml")
                + rel("rId2", REL + "/sharedStrings", "sharedStrings.xml")));
        entries.put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"" + s + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData></worksheet>");
        entries.put("xl/sharedStrings.xml", "<sst xmlns=\"" + s + "\" count=\"1\" uniqueCount=\"1\"><si><t xml:space=\"preserve\">"
                + escape(text) + "</t></si></sst>");
        return zip(entries);
    }

    /** A ZIP that is not an Office document. */
    public static byte[] plainZip() {
        return zip(Map.of("notes.txt", "Not an Office document."));
    }

    /** All text of the given package entry. */
    public static String entry(final byte[] pkg, final String name) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("No entry " + name);
    }

    public static byte[] zip(final Map<String, String> entries) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (final Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String contentTypes(final String overrides) {
        return "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"" + CT_RELS + "\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" + overrides + "</Types>";
    }

    private static String override(final String part, final String contentType) {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + contentType + "\"/>";
    }

    private static String rels(final String relationships) {
        return "<Relationships xmlns=\"" + PKG_REL + "\">" + relationships + "</Relationships>";
    }

    private static String rel(final String id, final String type, final String target) {
        return "<Relationship Id=\"" + id + "\" Type=\"" + type + "\" Target=\"" + target + "\"/>";
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
