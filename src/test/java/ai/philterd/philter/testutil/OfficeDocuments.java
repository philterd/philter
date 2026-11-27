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

    /**
     * A Word document with a paragraph of {@code text}, then a paragraph inserted as a tracked change by
     * {@code author}, who is also the document's creator in {@code docProps/core.xml}.
     */
    public static byte[] docxByAuthor(final String text, final String author) {
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
                        + override("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "word/document.xml")
                + rel("rId2", PKG_REL + "/metadata/core-properties", "docProps/core.xml")));
        entries.put("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p>"
                + "<w:p><w:ins w:id=\"1\" w:author=\"" + escape(author) + "\" w:date=\"2026-01-01T00:00:00Z\">"
                + "<w:r><w:t>Reviewed.</w:t></w:r></w:ins></w:p></w:body></w:document>");
        entries.put("docProps/core.xml", "<cp:coreProperties"
                + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:creator>" + escape(author) + "</dc:creator><cp:lastModifiedBy>" + escape(author)
                + "</cp:lastModifiedBy></cp:coreProperties>");
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

    /**
     * A Word document whose body paragraph has a comment, a tracked deletion, and a hidden run, with a header, so each
     * {@code config.word} setting has something to change. The comment says {@code "Ask about " + value}, the deletion
     * {@code "Gone " + value}, the hidden run {@code "Secret " + value}, and the header {@code "Header " + value}.
     * {@code author} wrote the comment and the deletion and is the creator; the title and a custom property are set.
     */
    public static byte[] docxWithEverySetting(final String value, final String author) {
        final String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
                        + override("/word/comments.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml")
                        + override("/word/header1.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml")
                        + properties()));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "word/document.xml") + propertyRels()));
        entries.put("word/_rels/document.xml.rels", rels(rel("rId1", REL + "/comments", "comments.xml")
                + rel("rId2", REL + "/header", "header1.xml")));
        entries.put("word/document.xml", "<w:document xmlns:w=\"" + w + "\" xmlns:r=\"" + REL + "\"><w:body>"
                + "<w:p><w:commentRangeStart w:id=\"1\"/><w:r><w:t xml:space=\"preserve\">Body text. </w:t></w:r>"
                + "<w:commentRangeEnd w:id=\"1\"/><w:r><w:commentReference w:id=\"1\"/></w:r>"
                + "<w:del w:id=\"2\" w:author=\"" + escape(author) + "\" w:date=\"2026-01-01T00:00:00Z\"><w:r>"
                + "<w:delText xml:space=\"preserve\">Gone " + escape(value) + " </w:delText></w:r></w:del>"
                + "<w:r><w:rPr><w:vanish/></w:rPr><w:t xml:space=\"preserve\">Secret " + escape(value) + "</w:t></w:r></w:p>"
                + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId2\"/></w:sectPr></w:body></w:document>");
        entries.put("word/comments.xml", "<w:comments xmlns:w=\"" + w + "\"><w:comment w:id=\"1\" w:author=\""
                + escape(author) + "\" w:initials=\"AE\"><w:p><w:r><w:t xml:space=\"preserve\">Ask about " + escape(value)
                + "</w:t></w:r></w:p></w:comment></w:comments>");
        entries.put("word/header1.xml", "<w:hdr xmlns:w=\"" + w + "\"><w:p><w:r><w:t xml:space=\"preserve\">Header "
                + escape(value) + "</w:t></w:r></w:p></w:hdr>");
        putProperties(entries, author);
        return zip(entries);
    }

    /**
     * An Excel workbook whose cell A1 holds {@code text}, with the print header {@code "Header " + value}, a title, a
     * custom property, and {@code author} as the creator.
     */
    public static byte[] xlsxWithEverySetting(final String text, final String value, final String author) {
        final String s = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/xl/workbook.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")
                        + override("/xl/worksheets/sheet1.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml")
                        + override("/xl/sharedStrings.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml")
                        + properties()));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "xl/workbook.xml") + propertyRels()));
        entries.put("xl/workbook.xml", "<workbook xmlns=\"" + s + "\" xmlns:r=\"" + REL + "\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        entries.put("xl/_rels/workbook.xml.rels", rels(rel("rId1", REL + "/worksheet", "worksheets/sheet1.xml")
                + rel("rId2", REL + "/sharedStrings", "sharedStrings.xml")));
        entries.put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"" + s + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row></sheetData>"
                + "<headerFooter><oddHeader>&amp;LHeader " + escape(value) + "</oddHeader></headerFooter></worksheet>");
        entries.put("xl/sharedStrings.xml", "<sst xmlns=\"" + s + "\" count=\"1\" uniqueCount=\"1\"><si><t xml:space=\"preserve\">"
                + escape(text) + "</t></si></sst>");
        putProperties(entries, author);
        return zip(entries);
    }

    private static final String CT_CHART = "application/vnd.openxmlformats-officedocument.drawingml.chart+xml";
    // A PowerPoint package, which redaction cannot inspect.
    private static final String CT_PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    // A chart whose title and cached category are "Chart for " + value and value.
    private static String chart(final String value) {
        return "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:chart>"
                + "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Chart for " + escape(value)
                + "</a:t></a:r></a:p></c:rich></c:tx></c:title><c:plotArea><c:layout/><c:barChart><c:ser>"
                + "<c:cat><c:strRef><c:f>Sheet1!$A$2</c:f><c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\"><c:v>"
                + escape(value) + "</c:v></c:pt></c:strCache></c:strRef></c:cat></c:ser></c:barChart></c:plotArea>"
                + "</c:chart></c:chartSpace>";
    }

    /**
     * A Word document with one paragraph of {@code text}, a chart whose title and cached category hold {@code value},
     * and an embedded object redaction cannot inspect, at {@code word/embeddings/}.
     */
    public static byte[] docxWithChartAndObject(final String text, final String value) {
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/word/document.xml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
                        + override("/word/charts/chart1.xml", CT_CHART)
                        + override("/word/embeddings/object1.pptx", CT_PPTX)));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "word/document.xml")));
        entries.put("word/_rels/document.xml.rels", rels(rel("rId1", REL + "/chart", "charts/chart1.xml")
                + rel("rId2", REL + "/package", "embeddings/object1.pptx")));
        entries.put("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p></w:body></w:document>");
        entries.put("word/charts/chart1.xml", chart(value));
        entries.put("word/embeddings/object1.pptx", "\u0001\u0002\u0003\u0004");
        return zip(entries);
    }

    /**
     * An Excel workbook whose cell A2 holds {@code value} and B2 a formula whose cached result is {@code value}, with a
     * chart whose title and cached category hold it, a pivot cache with it as a shared item, and an embedded object
     * redaction cannot inspect, at {@code xl/embeddings/}.
     */
    public static byte[] xlsxWithCachesAndObject(final String value) {
        final String s = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
        final String xdr = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
        final String sml = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(
                override("/xl/workbook.xml", sml + "sheet.main+xml")
                        + override("/xl/worksheets/sheet1.xml", sml + "worksheet+xml")
                        + override("/xl/drawings/drawing1.xml", "application/vnd.openxmlformats-officedocument.drawing+xml")
                        + override("/xl/charts/chart1.xml", CT_CHART)
                        + override("/xl/pivotCache/pivotCacheDefinition1.xml", sml + "pivotCacheDefinition+xml")
                        + override("/xl/pivotCache/pivotCacheRecords1.xml", sml + "pivotCacheRecords+xml")
                        + override("/xl/pivotTables/pivotTable1.xml", sml + "pivotTable+xml")
                        + override("/xl/embeddings/object1.pptx", CT_PPTX)));
        entries.put("_rels/.rels", rels(rel("rId1", REL + "/officeDocument", "xl/workbook.xml")));
        entries.put("xl/workbook.xml", "<workbook xmlns=\"" + s + "\" xmlns:r=\"" + REL + "\">"
                + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "<pivotCaches><pivotCache cacheId=\"1\" r:id=\"rId2\"/></pivotCaches></workbook>");
        entries.put("xl/_rels/workbook.xml.rels", rels(rel("rId1", REL + "/worksheet", "worksheets/sheet1.xml")
                + rel("rId2", REL + "/pivotCacheDefinition", "pivotCache/pivotCacheDefinition1.xml")));
        entries.put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"" + s + "\" xmlns:r=\"" + REL + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>SSN</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>Copy</t></is></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>" + escape(value) + "</t></is></c>"
                + "<c r=\"B2\" t=\"str\"><f>A2</f><v>" + escape(value) + "</v></c></row></sheetData>"
                + "<drawing r:id=\"rId1\"/></worksheet>");
        entries.put("xl/worksheets/_rels/sheet1.xml.rels", rels(rel("rId1", REL + "/drawing", "../drawings/drawing1.xml")
                + rel("rId2", REL + "/pivotTable", "../pivotTables/pivotTable1.xml")
                + rel("rId3", REL + "/package", "../embeddings/object1.pptx")));
        entries.put("xl/drawings/drawing1.xml", "<xdr:wsDr xmlns:xdr=\"" + xdr + "\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + REL + "\""
                + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><xdr:twoCellAnchor>"
                + "<xdr:from><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                + "<xdr:to><xdr:col>9</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>15</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>"
                + "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"2\" name=\"Chart 1\"/><xdr:cNvGraphicFramePr/>"
                + "</xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic>"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId1\"/>"
                + "</a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>");
        entries.put("xl/drawings/_rels/drawing1.xml.rels", rels(rel("rId1", REL + "/chart", "../charts/chart1.xml")));
        entries.put("xl/charts/chart1.xml", chart(value));
        entries.put("xl/pivotCache/pivotCacheDefinition1.xml", "<pivotCacheDefinition xmlns=\"" + s + "\" xmlns:r=\"" + REL
                + "\" r:id=\"rId1\" recordCount=\"1\" createdVersion=\"6\" refreshedVersion=\"6\" minRefreshableVersion=\"3\">"
                + "<cacheSource type=\"worksheet\"><worksheetSource ref=\"A1:A2\" sheet=\"Sheet1\"/></cacheSource>"
                + "<cacheFields count=\"1\"><cacheField name=\"SSN\" numFmtId=\"0\"><sharedItems count=\"1\"><s v=\""
                + escape(value) + "\"/></sharedItems></cacheField></cacheFields></pivotCacheDefinition>");
        entries.put("xl/pivotCache/_rels/pivotCacheDefinition1.xml.rels",
                rels(rel("rId1", REL + "/pivotCacheRecords", "pivotCacheRecords1.xml")));
        entries.put("xl/pivotCache/pivotCacheRecords1.xml", "<pivotCacheRecords xmlns=\"" + s + "\" count=\"1\"><r><x v=\"0\"/></r>"
                + "</pivotCacheRecords>");
        entries.put("xl/pivotTables/pivotTable1.xml", "<pivotTableDefinition xmlns=\"" + s + "\" name=\"PivotTable1\" cacheId=\"1\""
                + " dataCaption=\"Values\" createdVersion=\"6\" updatedVersion=\"6\" minRefreshableVersion=\"3\">"
                + "<location ref=\"H1:H3\" firstHeaderRow=\"1\" firstDataRow=\"1\" firstDataCol=\"0\"/><pivotFields count=\"1\">"
                + "<pivotField axis=\"axisRow\" showAll=\"0\"><items count=\"2\"><item x=\"0\"/><item t=\"default\"/></items>"
                + "</pivotField></pivotFields><rowFields count=\"1\"><field x=\"0\"/></rowFields><rowItems count=\"2\"><i><x/></i>"
                + "<i t=\"grand\"><x/></i></rowItems><colItems count=\"1\"><i/></colItems></pivotTableDefinition>");
        entries.put("xl/pivotTables/_rels/pivotTable1.xml.rels",
                rels(rel("rId1", REL + "/pivotCacheDefinition", "../pivotCache/pivotCacheDefinition1.xml")));
        entries.put("xl/embeddings/object1.pptx", "\u0001\u0002\u0003\u0004");
        return zip(entries);
    }

    private static String properties() {
        return override("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")
                + override("/docProps/custom.xml", "application/vnd.openxmlformats-officedocument.custom-properties+xml");
    }

    private static String propertyRels() {
        return rel("rId8", PKG_REL + "/metadata/core-properties", "docProps/core.xml")
                + rel("rId9", REL + "/custom-properties", "docProps/custom.xml");
    }

    private static void putProperties(final Map<String, String> entries, final String author) {
        entries.put("docProps/core.xml", "<cp:coreProperties"
                + " xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>Quarterly notes</dc:title><dc:creator>" + escape(author) + "</dc:creator>"
                + "<cp:lastModifiedBy>" + escape(author) + "</cp:lastModifiedBy></cp:coreProperties>");
        entries.put("docProps/custom.xml", "<Properties"
                + " xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/custom-properties\""
                + " xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
                + "<property fmtid=\"{D5CDD505-2E9C-101B-9397-08002B2CF9AE}\" pid=\"2\" name=\"Client\">"
                + "<vt:lpwstr>Example Client</vt:lpwstr></property></Properties>");
    }

    /** The names of the package's entries. */
    public static java.util.List<String> entryNames(final byte[] pkg) {
        final java.util.List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    /** The text of every entry in the package, concatenated. */
    public static String allEntries(final byte[] pkg) {
        final StringBuilder all = new StringBuilder();
        for (final String name : entryNames(pkg)) {
            all.append(entry(pkg, name)).append('\n');
        }
        return all.toString();
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
