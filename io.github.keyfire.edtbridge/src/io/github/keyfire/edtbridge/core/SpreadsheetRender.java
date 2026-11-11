/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.core;

import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Cell;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Grid;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.GridRow;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Layout;
import io.github.keyfire.edtbridge.core.SpreadsheetLayout.Placed;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * A picture of a {@link Layout} close to what the template editor shows: column numbers on top, row
 * numbers and area names on the left, the grid in thin grey, cell text with its alignment and weight,
 * parameters as {@code <Name>}, borders in black and merged cells as one. One unit of column width is
 * one pixel, a 10 pt font is drawn 13 pixels high, as on a 96 dpi screen, and a row grows to fit
 * wrapped or larger text.
 */
public final class SpreadsheetRender {
    // The class sets no java.awt.headless of its own: inside client EDT that would switch AWT windows
    // off for every other plug-in of the process. Drawing into a BufferedImage needs no display, and on
    // Linux without DISPLAY the runtime turns headless by itself.

    /** The largest picture drawn; a larger layout is refused rather than eating the heap of the IDE. */
    public static final long MAX_PIXELS = 24_000_000L;

    private static final int ROW_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 20;
    private static final int PAD = 3;
    private static final Color GRID = new Color(0xD4, 0xD4, 0xD4);
    private static final Color HEADER = new Color(0xF0, 0xF0, 0xF0);
    private static final Color HEADER_TEXT = new Color(0x60, 0x60, 0x60);
    private static final Color AREA = new Color(0x2F, 0x5C, 0x9E);
    private static final String SAMPLE = "АБВгдеЖЁё";
    private static final List<String> FAMILIES = List.of("Arial", "Liberation Sans", "DejaVu Sans", "Segoe UI",
            "Helvetica", Font.SANS_SERIF, Font.DIALOG);

    private static volatile String family;

    private SpreadsheetRender() { }

    /** PNG of the layout as the template editor shows it: grid, column widths, text, <Parameter>, borders, merges, area names. */
    public static byte[] png(Layout layout) {
        Grid grid = SpreadsheetLayout.grid(layout);
        BufferedImage image = draw(layout, grid);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("no PNG writer in this Java runtime");
            return out.toByteArray();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static BufferedImage draw(Layout layout, Grid grid) {
        String face = family();
        Font header = new Font(face, Font.PLAIN, 1).deriveFont(11f);
        Font areaFont = new Font(face, Font.BOLD, 1).deriveFont(11f);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D measure = probe.createGraphics();
        hints(measure);

        int[] widths = grid.widths();
        int[] left = new int[widths.length + 1];
        List<GridRow> rows = grid.rows();
        int numbers = measure.getFontMetrics(header).stringWidth(String.valueOf(Math.max(rows.size(), 1))) + 2 * PAD + 4;
        int gutter = 12;
        for (var area : layout.areas()) {
            if (!area.name().isEmpty()) gutter = Math.max(gutter, measure.getFontMetrics(areaFont).stringWidth(area.name()) + 18);
        }
        gutter = Math.min(gutter, 220);
        left[0] = gutter + numbers;
        for (int column = 0; column < widths.length; column++) left[column + 1] = left[column] + widths[column];

        int[] top = new int[rows.size() + 1];
        top[0] = HEADER_HEIGHT;
        List<List<Block>> blocks = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            List<Block> row = new ArrayList<>();
            int height = ROW_HEIGHT;
            for (Placed placed : rows.get(index).cells()) {
                Block block = block(placed, left, measure, face);
                row.add(block);
                height = Math.max(height, block.lines().size() * block.lineHeight() + 2 * PAD);
            }
            blocks.add(row);
            top[index + 1] = top[index] + height;
        }
        measure.dispose();

        int width = left[widths.length] + 1;
        int height = top[rows.size()] + 1;
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("the picture would be " + width + "x" + height + " pixels, more than "
                    + MAX_PIXELS + "; render fewer rows or narrower columns");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        hints(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        g.setColor(HEADER);
        g.fillRect(0, 0, width, HEADER_HEIGHT);
        g.fillRect(gutter, 0, numbers, height);
        g.setFont(header);
        FontMetrics small = g.getFontMetrics();
        g.setColor(GRID);
        for (int column = 0; column <= widths.length; column++) g.drawLine(left[column], 0, left[column], height - 1);
        for (int index = 0; index <= rows.size(); index++) g.drawLine(gutter, top[index], width - 1, top[index]);
        g.drawLine(gutter, 0, gutter, height - 1);
        g.drawLine(gutter, 0, width - 1, 0);
        g.setColor(HEADER_TEXT);
        for (int column = 0; column < widths.length; column++) {
            String label = String.valueOf(column + 1);
            int x = left[column] + (widths[column] - small.stringWidth(label)) / 2;
            g.drawString(label, x, (HEADER_HEIGHT - small.getHeight()) / 2 + small.getAscent());
        }
        for (int index = 0; index < rows.size(); index++) {
            String label = String.valueOf(index + 1);
            int x = gutter + numbers - PAD - 2 - small.stringWidth(label);
            g.drawString(label, x, top[index] + (top[index + 1] - top[index] - small.getHeight()) / 2 + small.getAscent());
        }

        for (int index = 0; index < rows.size(); index++) {
            for (Block block : blocks.get(index)) {
                int x = left[block.first()];
                int w = left[block.last() + 1] - x;
                int y = top[index];
                int h = top[index + 1] - y;
                if (block.last() > block.first()) {
                    g.setColor(Color.WHITE);
                    g.fillRect(x + 1, y + 1, w - 1, h - 1);
                }
                text(g, block, rows.get(index), left, x, y, w, h);
                if (Boolean.TRUE.equals(block.cell().border())) {
                    g.setColor(Color.BLACK);
                    g.setStroke(new BasicStroke(1f));
                    g.drawRect(x, y, w, h);
                }
            }
        }

        g.setFont(areaFont);
        FontMetrics names = g.getFontMetrics();
        int first = 0;
        for (int index = 0; index <= rows.size(); index++) {
            boolean boundary = index == rows.size() || rows.get(index).area() != rows.get(first).area();
            if (!boundary) continue;
            String name = layout.areas().get(rows.get(first).area()).name();
            if (!name.isEmpty()) {
                int y0 = top[first] + 2;
                int y1 = top[index] - 2;
                int x = gutter - 5;
                g.setColor(AREA);
                g.drawLine(x, y0, x, y1);
                g.drawLine(x, y0, x + 3, y0);
                g.drawLine(x, y1, x + 3, y1);
                g.setClip(0, top[first], gutter - 8, top[index] - top[first]);
                int textY = top[first] + (Math.min(top[index] - top[first], ROW_HEIGHT) - names.getHeight()) / 2 + names.getAscent();
                g.drawString(name, 4, textY);
                g.setClip(null);
            }
            first = index;
        }
        g.dispose();
        return image;
    }

    /** A cell ready to draw: its columns, its font and its text broken into lines. */
    private record Block(Cell cell, int first, int last, Font font, List<String> lines, int lineHeight) { }

    private static Block block(Placed placed, int[] left, Graphics2D measure, String face) {
        Cell cell = placed.cell();
        int last = placed.column() + placed.span() - 1;
        int size = cell.fontSize() == null ? SpreadsheetLayout.DEFAULT_FONT_SIZE : cell.fontSize();
        Font font = new Font(face, Boolean.TRUE.equals(cell.bold()) ? Font.BOLD : Font.PLAIN, 1).deriveFont(size * 96f / 72f);
        FontMetrics metrics = measure.getFontMetrics(font);
        String text = cell.parameter() != null ? "<" + cell.parameter() + ">"
                : cell.template() != null ? cell.template() : cell.text();
        List<String> lines = new ArrayList<>();
        int room = Math.max(1, left[last + 1] - left[placed.column()] - 2 * PAD);
        for (String paragraph : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (Boolean.TRUE.equals(cell.wrap())) wrap(paragraph, room, metrics, lines);
            else lines.add(paragraph);
        }
        if (text.isEmpty()) lines.clear();
        return new Block(cell, placed.column(), last, font, lines, metrics.getHeight());
    }

    /** Breaks a paragraph at spaces to fit the width; a word longer than the width is broken anywhere. */
    private static void wrap(String paragraph, int room, FontMetrics metrics, List<String> lines) {
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ", -1)) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= room) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) lines.add(line.toString());
            line.setLength(0);
            for (char c : word.toCharArray()) {
                if (line.length() > 0 && metrics.stringWidth(line.toString() + c) > room) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(c);
            }
        }
        lines.add(line.toString());
    }

    private static void text(Graphics2D g, Block block, GridRow row, int[] left, int x, int y, int w, int h) {
        if (block.lines().isEmpty()) return;
        String align = block.cell().align() == null ? "Left" : SpreadsheetLayout.alignment(block.cell().align());
        int clipRight = x + w;
        if (!Boolean.TRUE.equals(block.cell().wrap()) && "Left".equals(align)) {
            // Text that does not wrap runs on over the empty cells to its right, as in the editor.
            int column = block.last() + 1;
            while (column < left.length - 1 && free(row, column)) column++;
            clipRight = left[column];
        }
        g.setClip(x + 1, y + 1, clipRight - x - 1, h - 1);
        g.setFont(block.font());
        g.setColor(Color.BLACK);
        FontMetrics metrics = g.getFontMetrics();
        int blockHeight = block.lines().size() * block.lineHeight();
        int baseline = y + Math.max(PAD, (h - blockHeight) / 2) + metrics.getAscent();
        for (String line : block.lines()) {
            int textWidth = metrics.stringWidth(line);
            int textX = switch (align) {
                case "Center" -> x + (w - textWidth) / 2;
                case "Right" -> x + w - PAD - textWidth;
                default -> x + PAD;
            };
            g.drawString(line, textX, baseline);
            baseline += block.lineHeight();
        }
        g.setClip(null);
    }

    /** A column the text of a cell to its left may run over: no cell there, or a cell with an empty text. */
    private static boolean free(GridRow row, int column) {
        for (Placed placed : row.cells()) {
            if (column >= placed.column() && column < placed.column() + placed.span()) {
                return placed.cell().text() != null && placed.cell().text().isEmpty();
            }
        }
        return true;
    }

    private static void hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    /** The first family that is installed and draws Cyrillic; the logical sans serif font when none does. */
    static String family() {
        String found = family;
        if (found != null) return found;
        found = Font.SANS_SERIF;
        for (String candidate : FAMILIES) {
            Font font = new Font(candidate, Font.PLAIN, 12);
            boolean installed = font.getFamily(Locale.ROOT).equalsIgnoreCase(candidate)
                    || candidate.equals(Font.SANS_SERIF) || candidate.equals(Font.DIALOG);
            if (installed && font.canDisplayUpTo(SAMPLE) == -1) {
                found = candidate;
                break;
            }
        }
        family = found;
        return found;
    }
}
