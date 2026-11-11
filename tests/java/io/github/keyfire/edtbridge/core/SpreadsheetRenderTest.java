/*
 * Copyright 2026 edt-bridge contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.keyfire.edtbridge.core;

import static io.github.keyfire.edtbridge.core.SpreadsheetLayoutTest.area;
import static io.github.keyfire.edtbridge.core.SpreadsheetLayoutTest.parameter;
import static io.github.keyfire.edtbridge.core.SpreadsheetLayoutTest.row;
import static io.github.keyfire.edtbridge.core.SpreadsheetLayoutTest.styled;
import static io.github.keyfire.edtbridge.core.SpreadsheetLayoutTest.text;
import static org.junit.jupiter.api.Assertions.*;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Area;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Cell;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Layout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SpreadsheetRenderTest {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    static BufferedImage picture(Layout layout) throws IOException {
        byte[] png = SpreadsheetRender.png(layout);
        assertArrayEquals(SIGNATURE, Arrays.copyOf(png, SIGNATURE.length), "PNG signature");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "ImageIO reads the picture back");
        return image;
    }

    @Test void invoiceIsDrawnAsAPicture() throws IOException {
        BufferedImage image = picture(SpreadsheetLayoutTest.invoice());
        assertTrue(image.getWidth() > 30 + 72 + 200 + 90, "the columns and the left margin fit: " + image.getWidth());
        assertTrue(image.getHeight() > 20 * 7, "the header and six rows fit: " + image.getHeight());
        String out = System.getenv("MXL_RENDER_OUT");
        if (out != null && Files.isDirectory(Path.of(out))) {
            Files.write(Path.of(out, "invoice.png"), SpreadsheetRender.png(SpreadsheetLayoutTest.invoice()));
        }
    }

    @Test void widerColumnsMakeAWiderPicture() throws IOException {
        List<List<Cell>> rows = List.of(row(text("Код"), text("Наименование")));
        int narrow = picture(new Layout(Arrays.asList(null, null), List.of(new Area("Строка", rows)), List.of())).getWidth();
        int wide = picture(new Layout(Arrays.asList(null, 300), List.of(new Area("Строка", rows)), List.of())).getWidth();
        assertEquals(300 - SpreadsheetLayout.DEFAULT_WIDTH, wide - narrow);
    }

    @Test void boldTextIsDrawnDifferentlyFromRegular() throws IOException {
        BufferedImage regular = picture(new Layout(null, List.of(area("Шапка", row(text("Итого к оплате")))), List.of()));
        BufferedImage bold = picture(new Layout(null, List.of(area("Шапка",
                row(styled(text("Итого к оплате"), true, null, null, null, null, null)))), List.of()));
        assertEquals(regular.getWidth(), bold.getWidth());
        assertEquals(regular.getHeight(), bold.getHeight());
        assertFalse(Arrays.equals(pixels(regular), pixels(bold)));
    }

    @Test void areaNamesWidenTheLeftMarginAndBordersAreBlack() throws IOException {
        Cell boxed = styled(parameter("Сумма"), null, null, null, true, null, null);
        BufferedImage unnamed = picture(new Layout(null, List.of(area("", row(boxed))), List.of()));
        BufferedImage named = picture(new Layout(null, List.of(area("ДлинноеИмяОбласти", row(boxed))), List.of()));
        assertTrue(named.getWidth() > unnamed.getWidth());
        int black = 0;
        for (int pixel : pixels(unnamed)) if ((pixel & 0xffffff) == 0) black++;
        assertTrue(black > 2 * SpreadsheetLayout.DEFAULT_WIDTH, "the border is drawn: " + black + " black pixels");
    }

    @Test void wrappedTextMakesTheRowTaller() throws IOException {
        String long_ = "Оплата этого счета означает согласие с условиями поставки товара";
        int single = picture(new Layout(null, List.of(area("Подвал", row(text(long_)))), List.of())).getHeight();
        int wrapped = picture(new Layout(null, List.of(area("Подвал",
                row(styled(text(long_), null, null, null, null, null, true)))), List.of())).getHeight();
        assertTrue(wrapped > single, wrapped + " > " + single);
    }

    @Test void fontDrawsCyrillicWhenTheSystemHasOne() {
        String family = SpreadsheetRender.family();
        assertNotNull(family);
        if (!family.equals(Font.SANS_SERIF)) assertEquals(-1, new Font(family, Font.PLAIN, 12).canDisplayUpTo("Счет"));
    }

    @Test void oversizedPictureIsRefusedAndInvalidLayoutToo() {
        Layout huge = new Layout(Collections.nCopies(200, null),
                List.of(new Area("A", Collections.nCopies(2000, List.of()))), List.of());
        var refusal = assertThrows(IllegalArgumentException.class, () -> SpreadsheetRender.png(huge));
        assertTrue(refusal.getMessage().contains("pixels"), refusal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SpreadsheetRender.png(new Layout(null, List.of(), List.of())));
    }

    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
