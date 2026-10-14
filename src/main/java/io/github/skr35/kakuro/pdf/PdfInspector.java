package io.github.skr35.kakuro.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local verification utility: renders every PDF page to PNG and extracts its text. */
public final class PdfInspector {
    private PdfInspector() { }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: PdfInspector booklet.pdf preview-directory");
        Path folder = Path.of(args[1]);
        Files.createDirectories(folder);
        try (var pdf = Loader.loadPDF(Path.of(args[0]).toFile())) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            for (int page = 0; page < pdf.getNumberOfPages(); page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 110), "PNG", folder.resolve("page-" + (page + 1) + ".png").toFile());
            }
            Files.writeString(folder.resolve("text.txt"), new PDFTextStripper().getText(pdf));
            System.out.println("Rendered " + pdf.getNumberOfPages() + " pages into " + folder.toAbsolutePath());
        }
    }
}
