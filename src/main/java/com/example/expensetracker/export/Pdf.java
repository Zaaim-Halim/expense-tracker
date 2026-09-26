package com.example.expensetracker.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * A PDF of pictures: each page an image, on A4, centred between margins.
 *
 * <p>Written here rather than with a library: a report drawn by the
 * application only needs pages that each show one image, which takes a few
 * objects of the format, and no dependency is carried for it.
 */
public final class Pdf {

    /** An A4 page, in points. */
    public static final double PAGE_WIDTH = 595.28;
    public static final double PAGE_HEIGHT = 841.89;
    /** The margin on every side, in points: half an inch. */
    public static final double MARGIN = 36;

    /**
     * One page's picture.
     *
     * @param width  in pixels
     * @param height in pixels
     * @param argb   the pixels, row by row from the top, as 0xAARRGGBB;
     *               transparency is laid over white
     * @param scale  pixels per point: 2 draws two pixels in every point
     */
    public record Page(int width, int height, int[] argb, double scale) {
        public Page {
            if (width <= 0 || height <= 0 || argb.length != width * height || scale <= 0) {
                throw new IllegalArgumentException("a page's picture must have pixels");
            }
        }
    }

    private Pdf() {
    }

    /**
     * Writes the pages to {@code file}, all or nothing: into a file beside it
     * first, which then takes its place, so a failure leaves no half-written
     * PDF behind.
     */
    public static void write(Path file, String title, List<Page> pages) throws IOException {
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a PDF needs a page");
        }
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (OutputStream out = Files.newOutputStream(partial)) {
            out.write(bytes(title, pages));
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The whole document. */
    public static byte[] bytes(String title, List<Page> pages) throws IOException {
        Writer pdf = new Writer();
        pdf.text("%PDF-1.4\n%âãÏÓ\n");
        // Objects 1 and 2 are the catalogue and the page tree; each page then
        // takes three: the page, what it draws, and its image.
        int info = 3 + pages.size() * 3;
        List<Integer> kids = new ArrayList<>();
        for (int n = 0; n < pages.size(); n++) {
            kids.add(3 + n * 3);
        }
        pdf.object(1, "<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder list = new StringBuilder();
        for (int kid : kids) {
            list.append(kid).append(" 0 R ");
        }
        pdf.object(2, "<< /Type /Pages /Kids [" + list.toString().strip() + "] /Count " + pages.size() + " >>");
        for (int n = 0; n < pages.size(); n++) {
            Page page = pages.get(n);
            int pageId = 3 + n * 3;
            int contentId = pageId + 1;
            int imageId = pageId + 2;
            double width = page.width() / page.scale();
            double height = page.height() / page.scale();
            // Never wider or taller than the space inside the margins.
            double fit = Math.min(1, Math.min((PAGE_WIDTH - 2 * MARGIN) / width, (PAGE_HEIGHT - 2 * MARGIN) / height));
            width *= fit;
            height *= fit;
            double x = (PAGE_WIDTH - width) / 2;
            double y = PAGE_HEIGHT - MARGIN - height;
            pdf.object(pageId, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + number(PAGE_WIDTH) + " "
                    + number(PAGE_HEIGHT) + "] /Resources << /XObject << /Im " + imageId + " 0 R >> >> /Contents "
                    + contentId + " 0 R >>");
            byte[] draw = ("q " + number(width) + " 0 0 " + number(height) + " " + number(x) + " " + number(y)
                    + " cm /Im Do Q\n").getBytes(StandardCharsets.US_ASCII);
            pdf.stream(contentId, "<< /Length " + draw.length + " >>", draw);
            byte[] pixels = deflate(rgb(page));
            pdf.stream(imageId, "<< /Type /XObject /Subtype /Image /Width " + page.width() + " /Height "
                    + page.height() + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "
                    + pixels.length + " >>", pixels);
        }
        pdf.object(info, "<< /Title " + literal(title) + " /Producer (Expense Tracker) >>");
        return pdf.finish(info + 1, info);
    }

    /** The pixels as the format wants them: red, green, blue, a byte each, over white. */
    private static byte[] rgb(Page page) {
        byte[] out = new byte[page.width() * page.height() * 3];
        int at = 0;
        for (int pixel : page.argb()) {
            int alpha = pixel >>> 24;
            out[at++] = (byte) over(pixel >> 16 & 0xff, alpha);
            out[at++] = (byte) over(pixel >> 8 & 0xff, alpha);
            out[at++] = (byte) over(pixel & 0xff, alpha);
        }
        return out;
    }

    private static int over(int channel, int alpha) {
        return (channel * alpha + 255 * (255 - alpha) + 127) / 255;
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 8);
        try (DeflaterOutputStream deflating = new DeflaterOutputStream(out, new Deflater(Deflater.BEST_COMPRESSION))) {
            deflating.write(data);
        }
        return out.toByteArray();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** A string the format reads back as written: its own escapes, and only printable ASCII. */
    static String literal(String text) {
        StringBuilder out = new StringBuilder("(");
        for (char c : text.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                out.append('\\').append(c);
            } else if (c >= 32 && c < 127) {
                out.append(c);
            } else {
                out.append('?');
            }
        }
        return out.append(')').toString();
    }

    /** Numbered objects, and where each starts, for the table at the end. */
    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<long[]> offsets = new ArrayList<>();

        void text(String text) {
            out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
        }

        void object(int id, String body) {
            offsets.add(new long[] {id, out.size()});
            text(id + " 0 obj\n" + body + "\nendobj\n");
        }

        void stream(int id, String dictionary, byte[] data) {
            offsets.add(new long[] {id, out.size()});
            text(id + " 0 obj\n" + dictionary + "\nstream\n");
            out.writeBytes(data);
            text("\nendstream\nendobj\n");
        }

        byte[] finish(int size, int info) {
            long table = out.size();
            long[] where = new long[size];
            for (long[] offset : offsets) {
                where[(int) offset[0]] = offset[1];
            }
            StringBuilder xref = new StringBuilder("xref\n0 " + size + "\n0000000000 65535 f \n");
            for (int id = 1; id < size; id++) {
                xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", where[id]));
            }
            text(xref.toString());
            text("trailer\n<< /Size " + size + " /Root 1 0 R /Info " + info + " 0 R >>\nstartxref\n" + table
                    + "\n%%EOF\n");
            return out.toByteArray();
        }
    }
}
