package io.github.skr35.kakuro.pdf;

import org.apache.pdfbox.pdmodel.common.PDRectangle;

/** No-bleed, left-to-right paperback layout. Public margin inputs are inches. */
public record PrintLayout(String preset, Double inner, double outer, double top, double bottom, double gutter) {
    public PrintLayout {
        if (!preset.equals("kdp-8.25") && !preset.equals("kdp-8.50"))
            throw new IllegalArgumentException("Page size must be a4, kdp-8.25 or kdp-8.50");
        for (double value : new double[]{inner == null ? .625 : inner, outer, top, bottom, gutter})
            if (!Double.isFinite(value) || value < 0 || value > 11)
                throw new IllegalArgumentException("Margins/gutter must be finite, nonnegative inches");
        if (outer < .25 || top < .25 || bottom < .25)
            throw new IllegalArgumentException("No-bleed outer, top and bottom margins must be at least 0.25 inches");
        double effectiveInner = (inner == null ? .625 : inner) + gutter;
        if (effectiveInner < .375) throw new IllegalArgumentException("Inside margin including gutter must be at least 0.375 inches");
        if ((preset.equals("kdp-8.25") ? 594 : 612) - 72 * (effectiveInner + outer) < 240
                || 792 - 72 * (top + bottom) < 240)
            throw new IllegalArgumentException("Margins leave too little printable space");
    }

    public static PrintLayout defaults(String preset) { return new PrintLayout(preset, null, .5, .5, .5, 0); }
    public float width() { return preset.equals("kdp-8.25") ? 594 : 612; }
    public float height() { return 792; }
    public PDRectangle mediaBox() { return new PDRectangle(width(), height()); }
    public static double minimumInner(int pages) {
        if (pages < 1) throw new IllegalArgumentException("Page count must be positive");
        return pages <= 150 ? .375 : pages <= 300 ? .5 : pages <= 500 ? .625 : pages <= 700 ? .75 : .875;
    }
    public float inside(int pages) {
        return (float)(72 * ((inner == null ? Math.max(.625, minimumInner(pages)) : inner) + gutter));
    }
    public float left(int page, int pages) { return page % 2 == 1 ? inside(pages) : (float)(outer * 72); }
    public float right(int page, int pages) { return page % 2 == 1 ? (float)(outer * 72) : inside(pages); }
    public void validate(int pages) {
        if (pages > 590) throw new IllegalArgumentException("KDP layout supports at most 590 pages (black ink, white paper)");
        if (inside(pages) + .001 < minimumInner(pages) * 72)
            throw new IllegalArgumentException("Inside margin including gutter is too small for " + pages + " pages");
        if (width() - inside(pages) - outer * 72 < 240 || height() - (top + bottom) * 72 < 240)
            throw new IllegalArgumentException("Margins leave too little printable space");
    }
    public record Grid(float left, float bottom, float cell) { }
    public Grid grid(int rows, int cols, int page, int pages) {
        validate(pages);
        float x = left(page, pages) + 1;
        float y = (float)(bottom * 72) + 48;
        float w = width() - left(page, pages) - right(page, pages) - 2;
        float h = height() - (float)((top + bottom) * 72) - 130;
        float cell = Math.min(w / cols, h / rows);
        if (cell < 24) throw new IllegalArgumentException("Grid cannot fit with legible 7-point clues; reduce margins or choose a larger trim size");
        return new Grid(x + (w - cols * cell) / 2, y + (h - rows * cell) / 2, cell);
    }
}
