package io.github.skr35.kakuro.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.nio.file.Path;

/** Structural/text preflight of generated interiors; not a substitute for KDP Print Previewer. */
public final class KdpPdfValidator {
    private KdpPdfValidator() { }
    public static void validate(Path file, PrintLayout layout, int expectedPages) throws IOException {
        layout.validate(expectedPages);
        try (var document = Loader.loadPDF(file.toFile())) {
            if (document.isEncrypted() || document.getNumberOfPages() != expectedPages)
                throw new IOException("Invalid interior page count or encryption");
            for (var page : document.getPages()) {
                if (page.getRotation() != 0) throw new IOException("Print pages must remain portrait");
                for (PDRectangle box : new PDRectangle[]{page.getMediaBox(), page.getCropBox(), page.getTrimBox(), page.getBleedBox()})
                    if (Math.abs(box.getWidth()-layout.width()) > .01 || Math.abs(box.getHeight()-layout.height()) > .01
                            || box.getLowerLeftX() != 0 || box.getLowerLeftY() != 0)
                        throw new IOException("Incorrect no-bleed page boxes");
                for (var name : page.getResources().getFontNames())
                    if (!page.getResources().getFont(name).isEmbedded()) throw new IOException("Unembedded print font");
            }
            var textCheck = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition text) {
                    int page = getCurrentPageNo();
                    float x = text.getXDirAdj(), y = text.getYDirAdj();
                    if (text.getFontSizeInPt() < 6.99 || x < layout.left(page, expectedPages) - .1
                            || x + text.getWidthDirAdj() > layout.width() - layout.right(page, expectedPages) + .1
                            || y - text.getHeightDir() < layout.top() * 72 - .1
                            || y > layout.height() - layout.bottom() * 72 + .1)
                        throw new IllegalArgumentException("Text outside print safe area or below 7 points on page " + page);
                    super.processTextPosition(text);
                }
            };
            try { textCheck.getText(document); }
            catch (IllegalArgumentException e) { throw new IOException("Print preflight failed: " + e.getMessage(), e); }
        }
    }
}
