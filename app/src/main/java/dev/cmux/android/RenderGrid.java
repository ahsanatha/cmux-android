package dev.cmux.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class RenderGrid {
    private final List<String> history = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Map<Integer, TerminalSnapshot.Style> styles = new HashMap<>();
    private String surfaceId;
    private int columns;
    private long lastStateSeq = -1;
    private int cursorRow = -1;
    private int cursorColumn = -1;
    private boolean cursorVisible;
    private String activeScreen = "primary";
    private String foreground = "#fdfff1";
    private String background = "#272822";
    private String cursorColor = "#c0c1b5";
    private String cursorTextColor;

    synchronized void selectSurface(String selectedSurfaceId) {
        surfaceId = selectedSurfaceId;
        history.clear();
        rows.clear();
        styles.clear();
        columns = 0;
        lastStateSeq = -1;
        activeScreen = "primary";
    }

    synchronized boolean apply(JSONObject envelope) {
        JSONObject frame = envelope.optJSONObject("render_grid");
        if (frame == null) frame = envelope;
        if (!"cmux.render-grid.v1".equals(frame.optString("format"))) return false;
        String incomingSurface = frame.optString("surface_id");
        if (incomingSurface.isBlank()
            || surfaceId != null && !surfaceId.equalsIgnoreCase(incomingSurface)) return false;

        int count = frame.optInt("rows", 0);
        int incomingColumns = frame.optInt("columns", 0);
        if (count <= 0 || incomingColumns <= 0) return false;
        long stateSeq = frame.optLong("state_seq", frame.optLong("seq", 0));
        boolean full = frame.optBoolean("full", true);
        if (lastStateSeq >= 0
            && (stateSeq < lastStateSeq || !full && stateSeq == lastStateSeq)) return false;
        if (!full && (rows.size() != count || columns != incomingColumns)) return false;

        surfaceId = incomingSurface;
        columns = incomingColumns;
        String incomingScreen = frame.optString("active_screen", activeScreen);
        if ("primary".equals(incomingScreen) || "alternate".equals(incomingScreen)) {
            activeScreen = incomingScreen;
        }
        applyTheme(frame);
        applyStyles(frame.optJSONArray("styles"));
        JSONObject cursor = frame.optJSONObject("cursor");
        cursorRow = cursor == null ? -1 : cursor.optInt("row", -1);
        cursorColumn = cursor == null ? -1 : cursor.optInt("column", -1);
        cursorVisible = cursor != null && cursor.optBoolean("visible", true)
            && cursorRow >= 0 && cursorRow < count && cursorColumn >= 0 && cursorColumn < columns;

        if (full) {
            rows.clear();
            for (int i = 0; i < count; i++) rows.add(new Row(columns));
            history.clear();
            int historyCount = Math.max(0, frame.optInt("scrollback_rows", 0));
            history.addAll(Collections.nCopies(historyCount, ""));
            applyHistorySpans(frame.optJSONArray("scrollback_spans"));
        } else {
            int scrolled = "screen".equals(frame.optString("anchor"))
                ? Math.min(Math.max(0, frame.optInt("scrolled_rows", 0)), rows.size()) : 0;
            for (int i = 0; i < scrolled; i++) {
                history.add(rows.remove(0).text(0));
                rows.add(new Row(columns));
            }
            JSONArray cleared = frame.optJSONArray("cleared_rows");
            if (cleared != null) for (int i = 0; i < cleared.length(); i++) {
                int row = cleared.optInt(i, -1);
                if (row >= 0 && row < rows.size()) rows.get(row).clear();
            }
        }
        applyRowSpans(frame.optJSONArray("row_spans"));
        if (history.size() > 20_000) history.subList(0, history.size() - 20_000).clear();
        lastStateSeq = Math.max(lastStateSeq, stateSeq);
        return true;
    }

    synchronized String text() {
        List<String> all = new ArrayList<>(history.size() + rows.size());
        all.addAll(history);
        for (Row row : rows) all.add(row.text(0));
        int end = all.size();
        while (end > 0 && all.get(end - 1).isBlank()) end--;
        return String.join("\n", all.subList(0, end));
    }

    synchronized String viewportText() {
        List<String> lines = new ArrayList<>(rows.size());
        for (Row row : rows) lines.add(row.text(0));
        return String.join("\n", lines);
    }

    synchronized TerminalSnapshot viewportSnapshot() {
        return snapshot(false);
    }

    synchronized TerminalSnapshot snapshot() {
        return snapshot(true);
    }

    synchronized boolean isPrimaryScreen() {
        return !"alternate".equals(activeScreen);
    }

    synchronized int historyRows() {
        return history.size();
    }

    private TerminalSnapshot snapshot(boolean includeHistory) {
        StringBuilder text = new StringBuilder();
        List<TerminalSnapshot.Run> runs = new ArrayList<>();
        int cursorOffset = -1;
        if (includeHistory) for (String line : history) text.append(line).append('\n');
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            int requiredColumns = rowIndex == cursorRow && cursorVisible ? cursorColumn + 1 : 0;
            Row.Rendered rendered = row.render(requiredColumns);
            int base = text.length();
            text.append(rendered.text);
            for (Row.StyleRun run : rendered.runs) {
                runs.add(new TerminalSnapshot.Run(base + run.start, base + run.end,
                    styles.getOrDefault(run.styleId, defaultStyle())));
            }
            if (rowIndex == cursorRow && cursorVisible) {
                cursorOffset = base + rendered.offsetForColumn(cursorColumn);
            }
            if (rowIndex + 1 < rows.size()) text.append('\n');
        }
        return new TerminalSnapshot(text.toString(), runs, foreground, background,
            cursorColor, cursorTextColor, cursorOffset, cursorVisible);
    }

    private void applyTheme(JSONObject frame) {
        JSONObject theme = frame.optJSONObject("terminal_theme");
        if (theme != null) {
            foreground = validColor(theme.optString("foreground"), foreground);
            background = validColor(theme.optString("background"), background);
            cursorColor = validColor(theme.optString("cursor"), cursorColor);
            cursorTextColor = validColor(theme.optString("cursor_text"), cursorTextColor);
        }
        foreground = validColor(frame.optString("terminal_foreground"), foreground);
        background = validColor(frame.optString("terminal_background"), background);
        cursorColor = validColor(frame.optString("terminal_cursor_color"), cursorColor);
    }

    private void applyStyles(JSONArray incoming) {
        if (incoming == null) return;
        styles.clear();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject style = incoming.optJSONObject(i);
            if (style == null) continue;
            styles.put(style.optInt("id", 0), new TerminalSnapshot.Style(
                colorOrNull(style.optString("foreground")),
                colorOrNull(style.optString("background")),
                style.optBoolean("bold"), style.optBoolean("faint"),
                style.optBoolean("italic"), style.optBoolean("underline"),
                style.optBoolean("inverse"), style.optBoolean("invisible"),
                style.optBoolean("strikethrough")));
        }
    }

    private void applyRowSpans(JSONArray spans) {
        if (spans == null) return;
        for (int i = 0; i < spans.length(); i++) {
            JSONObject span = spans.optJSONObject(i);
            if (span == null) continue;
            int row = span.optInt("row", -1);
            int column = span.optInt("column", -1);
            if (row < 0 || row >= rows.size() || column < 0 || column >= columns) continue;
            String value = span.optString("text", "");
            int fallbackWidth = Math.max(1, value.codePointCount(0, value.length()));
            int width = Math.min(columns - column,
                Math.max(1, span.optInt("grid_cell_width", fallbackWidth)));
            rows.get(row).put(column, width, value, span.optInt("style_id", 0));
        }
    }

    private void applyHistorySpans(JSONArray spans) {
        if (spans == null || history.isEmpty()) return;
        List<Row> rendered = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) rendered.add(new Row(columns));
        for (int i = 0; i < spans.length(); i++) {
            JSONObject span = spans.optJSONObject(i);
            if (span == null) continue;
            int row = span.optInt("row", -1);
            int column = span.optInt("column", -1);
            if (row < 0 || row >= rendered.size() || column < 0 || column >= columns) continue;
            String value = span.optString("text", "");
            int width = Math.min(columns - column, Math.max(1,
                span.optInt("grid_cell_width", Math.max(1, value.codePointCount(0, value.length())))));
            rendered.get(row).put(column, width, value, span.optInt("style_id", 0));
        }
        for (int i = 0; i < rendered.size(); i++) history.set(i, rendered.get(i).text(0));
    }

    private TerminalSnapshot.Style defaultStyle() {
        return styles.getOrDefault(0, new TerminalSnapshot.Style(
            null, null, false, false, false, false, false, false, false));
    }

    private static String validColor(String value, String fallback) {
        String parsed = colorOrNull(value);
        return parsed == null ? fallback : parsed;
    }

    private static String colorOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.startsWith("#") ? value : "#" + value;
        return normalized.matches("#[0-9a-fA-F]{6}") ? normalized : null;
    }

    private static final class Row {
        private final String[] cells;
        private final int[] styleIds;

        Row(int columns) {
            cells = new String[columns];
            styleIds = new int[columns];
        }

        void clear() {
            java.util.Arrays.fill(cells, null);
            java.util.Arrays.fill(styleIds, 0);
        }

        void put(int column, int width, String value, int styleId) {
            int end = Math.min(cells.length, column + width);
            for (int i = column; i < end; i++) {
                cells[i] = i == column ? value : "";
                styleIds[i] = styleId;
            }
        }

        String text(int requiredColumns) {
            return render(requiredColumns).text;
        }

        Rendered render(int requiredColumns) {
            int last = Math.min(cells.length, Math.max(0, requiredColumns));
            for (int i = cells.length - 1; i >= 0; i--) {
                if (cells[i] != null || styleIds[i] != 0) {
                    last = Math.max(last, i + 1);
                    break;
                }
            }
            int[] offsets = new int[last + 1];
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < last; i++) {
                offsets[i] = text.length();
                String cell = cells[i];
                if (cell == null) text.append(' ');
                else text.append(cell);
            }
            offsets[last] = text.length();
            List<StyleRun> runs = new ArrayList<>();
            int start = 0;
            while (start < last) {
                int style = styleIds[start];
                int end = start + 1;
                while (end < last && styleIds[end] == style) end++;
                if (style != 0 && offsets[start] < offsets[end]) {
                    runs.add(new StyleRun(offsets[start], offsets[end], style));
                }
                start = end;
            }
            return new Rendered(text.toString(), offsets, runs);
        }

        private record StyleRun(int start, int end, int styleId) {}

        private record Rendered(String text, int[] offsets, List<StyleRun> runs) {
            int offsetForColumn(int column) {
                if (offsets.length == 0) return 0;
                return offsets[Math.max(0, Math.min(column, offsets.length - 1))];
            }
        }
    }
}
