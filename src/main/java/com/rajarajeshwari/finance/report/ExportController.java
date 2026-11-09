package com.rajarajeshwari.finance.report;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.rajarajeshwari.finance.config.ConfigService;
import com.rajarajeshwari.finance.support.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Turns the table currently shown on screen into a PDF or CSV (opens in Excel).
 * Request: {title, subtitle, headers:[..], align:["L","R",..], rows:[[..]], footer:[..]}
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ConfigService config;

    public ExportController(ConfigService config) {
        this.config = config;
    }

    @PostMapping("/pdf")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> pdf(@RequestBody Map<String, Object> body) throws DocumentException {
        List<String> headers = (List<String>) body.get("headers");
        List<List<Object>> rows = (List<List<Object>>) body.get("rows");
        List<Object> footer = (List<Object>) body.get("footer");
        List<String> align = (List<String>) body.get("align");
        if (headers == null || headers.isEmpty() || rows == null) {
            throw new ApiException(400, "Nothing to export");
        }
        String title = str(body.get("title"));
        String subtitle = str(body.get("subtitle"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(headers.size() > 7 ? PageSize.A4.rotate() : PageSize.A4, 28, 28, 30, 30);
        PdfWriter.getInstance(doc, out);
        doc.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

        Paragraph head = new Paragraph(config.appName(),FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.GRAY));
        head.setAlignment(Element.ALIGN_CENTER);
        doc.add(head);
        Paragraph t = new Paragraph(title, titleFont);
        t.setAlignment(Element.ALIGN_CENTER);
        doc.add(t);
        if (!subtitle.isEmpty()) {
            Paragraph s = new Paragraph(subtitle, subFont);
            s.setAlignment(Element.ALIGN_CENTER);
            s.setSpacingAfter(8);
            doc.add(s);
        }

        PdfPTable table = new PdfPTable(headers.size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(new Color(0x2f, 0x4f, 0x6f));
            c.setPadding(4);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(c);
        }
        boolean zebra = false;
        for (List<Object> r : rows) {
            for (int i = 0; i < headers.size(); i++) {
                PdfPCell c = new PdfPCell(new Phrase(i < r.size() ? str(r.get(i)) : "", cellFont));
                c.setPadding(3);
                c.setHorizontalAlignment(alignOf(align, i));
                c.setBorderColor(Color.LIGHT_GRAY);
                if (zebra) {
                    c.setBackgroundColor(new Color(0xf3, 0xf6, 0xf9));
                }
                table.addCell(c);
            }
            zebra = !zebra;
        }
        if (footer != null) {
            for (int i = 0; i < headers.size(); i++) {
                PdfPCell c = new PdfPCell(new Phrase(i < footer.size() ? str(footer.get(i)) : "", boldFont));
                c.setPadding(4);
                c.setHorizontalAlignment(alignOf(align, i));
                c.setBackgroundColor(new Color(0xe3, 0xe9, 0xf0));
                c.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
                table.addCell(c);
            }
        }
        doc.add(table);
        doc.close();
        return download(out.toByteArray(), "application/pdf", fileName(title) + ".pdf");
    }

    @PostMapping("/csv")
    @SuppressWarnings("unchecked")
    public ResponseEntity<byte[]> csv(@RequestBody Map<String, Object> body) {
        List<String> headers = (List<String>) body.get("headers");
        List<List<Object>> rows = (List<List<Object>>) (body.get("csvRows") != null ? body.get("csvRows") : body.get("rows"));
        List<Object> footer = (List<Object>) (body.get("csvFooter") != null ? body.get("csvFooter") : body.get("footer"));
        if (headers == null || headers.isEmpty() || rows == null) {
            throw new ApiException(400, "Nothing to export");
        }
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(line(new java.util.ArrayList<Object>(headers)));
        for (List<Object> r : rows) {
            sb.append(line(r));
        }
        if (footer != null) {
            sb.append(line(footer));
        }
        return download(sb.toString().getBytes(StandardCharsets.UTF_8), "text/csv; charset=UTF-8",
                fileName(str(body.get("title"))) + ".csv");
    }

    private static String line(List<Object> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(str(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        return sb.append("\r\n").toString();
    }

    private static int alignOf(List<String> align, int i) {
        String a = align != null && i < align.size() ? align.get(i) : "L";
        return "R".equals(a) ? Element.ALIGN_RIGHT : "C".equals(a) ? Element.ALIGN_CENTER : Element.ALIGN_LEFT;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String fileName(String title) {
        String n = title.replaceAll("[^A-Za-z0-9 _-]", "").trim().replace(' ', '_');
        return n.isEmpty() ? "report" : n;
    }

    private static ResponseEntity<byte[]> download(byte[] data, String type, String name) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(data);
    }
}
