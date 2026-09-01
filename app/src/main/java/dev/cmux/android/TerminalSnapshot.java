package dev.cmux.android;

import java.util.List;

final class TerminalSnapshot {
    final String text;
    final List<Run> runs;
    final String foreground;
    final String background;
    final String cursorColor;
    final String cursorTextColor;
    final int cursorOffset;
    final boolean cursorVisible;

    TerminalSnapshot(String text, List<Run> runs, String foreground, String background,
                     String cursorColor, String cursorTextColor, int cursorOffset,
                     boolean cursorVisible) {
        this.text = text;
        this.runs = runs;
        this.foreground = foreground;
        this.background = background;
        this.cursorColor = cursorColor;
        this.cursorTextColor = cursorTextColor;
        this.cursorOffset = cursorOffset;
        this.cursorVisible = cursorVisible;
    }

    static final class Run {
        final int start;
        final int end;
        final Style style;

        Run(int start, int end, Style style) {
            this.start = start;
            this.end = end;
            this.style = style;
        }
    }

    static final class Style {
        final String foreground;
        final String background;
        final boolean bold;
        final boolean faint;
        final boolean italic;
        final boolean underline;
        final boolean inverse;
        final boolean invisible;
        final boolean strikethrough;

        Style(String foreground, String background, boolean bold, boolean faint,
              boolean italic, boolean underline, boolean inverse, boolean invisible,
              boolean strikethrough) {
            this.foreground = foreground;
            this.background = background;
            this.bold = bold;
            this.faint = faint;
            this.italic = italic;
            this.underline = underline;
            this.inverse = inverse;
            this.invisible = invisible;
            this.strikethrough = strikethrough;
        }
    }
}
