package com.example.expensetracker.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PDF a report is saved as: well formed, and showing exactly the pixels it was given. */
class PdfTest {

    @TempDir Path dir;

    private static Pdf.Page page(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return new Pdf.Page(width, height, pixels, 2);
    }

    @Test
    void every_object_is_where_the_table_says_and_the_trailer_points_at_the_table() throws Exception {
        byte[] pdf = Pdf.bytes("Report", List.of(page(20, 10, 0xff4f46e5), page(20, 30, 0xffffffff)));
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4\n"));
        assertTrue(text.endsWith("%%EOF\n"));

        Matcher start = Pattern.compile("startxref\n(\\d+)\n").matcher(text);
        assertTrue(start.find());
        int table = Integer.parseInt(start.group(1));
        assertTrue(text.startsWith("xref\n0 10\n", table), "catalogue, tree, 3 per page, info, and entry 0");

        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(text.substring(table));
        int id = 1;
        while (entries.find()) {
            int offset = Integer.parseInt(entries.group(1));
            assertTrue(text.startsWith(id + " 0 obj\n", offset), "object " + id + " at " + offset);
            id++;
        }
        assertEquals(10, id);
        assertTrue(text.contains("/Count 2"));
    }

    @Test
    void the_image_holds_the_pixels_laid_over_white() throws Exception {
        // One opaque indigo pixel and one half-transparent black one.
        Pdf.Page page = new Pdf.Page(2, 1, new int[] {0xff4f46e5, 0x80000000}, 1);
        byte[] pdf = Pdf.bytes("x", List.of(page));
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher image = Pattern.compile("/FlateDecode /Length (\\d+) >>\nstream\n").matcher(text);
        assertTrue(image.find());
        int length = Integer.parseInt(image.group(1));
        byte[] packed = java.util.Arrays.copyOfRange(pdf, image.end(), image.end() + length);
        ByteArrayOutputStream pixels = new ByteArrayOutputStream();
        try (InflaterInputStream in = new InflaterInputStream(new java.io.ByteArrayInputStream(packed))) {
            in.transferTo(pixels);
        }
        assertArrayEquals(new byte[] {0x4f, 0x46, (byte) 0xe5, 127, 127, 127}, pixels.toByteArray());
        assertTrue(text.startsWith("endstream", image.end() + length + 1));
    }

    @Test
    void a_title_cannot_break_out_of_its_string() {
        assertEquals("(Food \\(and drink\\) \\\\ caf?)", Pdf.literal("Food (and drink) \\ café"));
    }

    @Test
    void a_file_is_written_whole_or_not_at_all() throws Exception {
        Path file = dir.resolve("report.pdf");
        Pdf.write(file, "Report", List.of(page(4, 4, 0xff000000)));
        assertTrue(Files.size(file) > 0);
        assertFalse(Files.exists(dir.resolve("report.pdf.partial")));
        assertThrows(IllegalArgumentException.class, () -> Pdf.write(dir.resolve("none.pdf"), "x", List.of()));
        assertFalse(Files.exists(dir.resolve("none.pdf")));
    }
}
