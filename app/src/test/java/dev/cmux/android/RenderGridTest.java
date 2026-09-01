package dev.cmux.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class RenderGridTest {
    @Test public void appliesFullFrameThenDelta() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        JSONObject full = frame(true)
            .put("row_spans", new JSONArray()
                .put(span(0, 0, "hello"))
                .put(span(1, 2, "cmux")));
        assertTrue(grid.apply(full));
        assertEquals("hello\n  cmux", grid.text());

        JSONObject delta = frame(false)
            .put("cleared_rows", new JSONArray().put(1))
            .put("row_spans", new JSONArray().put(span(1, 0, "android")));
        assertTrue(grid.apply(delta));
        assertEquals("hello\nandroid", grid.text());
        assertEquals("hello\nandroid", grid.viewportText());
    }

    @Test public void viewportDeltaDoesNotScrollRows() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        assertTrue(grid.apply(frame(true)
            .put("row_spans", new JSONArray()
                .put(span(0, 0, "top"))
                .put(span(1, 0, "bottom")))));

        assertTrue(grid.apply(frame(false)
            .put("anchor", "viewport")
            .put("scrolled_rows", 1)
            .put("cleared_rows", new JSONArray().put(1))
            .put("row_spans", new JSONArray().put(span(1, 0, "updated")))));

        assertEquals("top\nupdated", grid.viewportText());
    }

    @Test public void screenAnchorKeepsLocalScrollbackWithoutMovingViewport() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        assertTrue(grid.apply(frame(true).put("anchor", "screen").put("active_screen", "primary")
            .put("row_spans", new JSONArray()
                .put(span(0, 0, "old"))
                .put(span(1, 0, "current")))));
        assertTrue(grid.apply(frame(false).put("anchor", "screen").put("active_screen", "primary")
            .put("scrolled_rows", 1)
            .put("row_spans", new JSONArray().put(span(1, 0, "next")))));

        assertEquals("current\nnext", grid.viewportText());
        assertEquals("old\ncurrent\nnext", grid.snapshot().text);
        assertEquals(1, grid.historyRows());
        assertTrue(grid.isPrimaryScreen());
        assertFalse(grid.apply(frame(false).put("anchor", "screen").put("state_seq", 11)
            .put("scrolled_rows", 1)));
        assertEquals("old\ncurrent\nnext", grid.snapshot().text);
    }

    @Test public void preservesThemeStylesAndCursor() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        JSONObject themed = frame(true)
            .put("columns", 8)
            .put("terminal_theme", new JSONObject()
                .put("foreground", "#eeeeee")
                .put("background", "#111111")
                .put("cursor", "#ffcc00")
                .put("cursor_text", "#000000"))
            .put("styles", new JSONArray()
                .put(new JSONObject().put("id", 0))
                .put(new JSONObject().put("id", 1)
                    .put("foreground", "#00ff00")
                    .put("bold", true)))
            .put("cursor", new JSONObject().put("row", 1).put("column", 3).put("visible", true))
            .put("row_spans", new JSONArray().put(
                span(0, 0, "green").put("style_id", 1)));

        assertTrue(grid.apply(themed));
        TerminalSnapshot snapshot = grid.viewportSnapshot();
        assertEquals("#eeeeee", snapshot.foreground);
        assertEquals("#111111", snapshot.background);
        assertEquals("#ffcc00", snapshot.cursorColor);
        assertEquals(1, snapshot.runs.size());
        assertEquals("#00ff00", snapshot.runs.get(0).style.foreground);
        assertTrue(snapshot.runs.get(0).style.bold);
        assertTrue(snapshot.cursorOffset > 0);
    }

    @Test public void rejectsReplayOlderThanDeliveredLiveFrame() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        assertTrue(grid.apply(frame(true).put("state_seq", 20)
            .put("row_spans", new JSONArray().put(span(0, 0, "new")))));
        assertFalse(grid.apply(frame(true).put("state_seq", 19)
            .put("row_spans", new JSONArray().put(span(0, 0, "stale")))));
        assertEquals("new\n", grid.viewportText());
    }

    @Test public void mapsWideGlyphGridColumnsToTextOffsets() throws Exception {
        RenderGrid grid = new RenderGrid();
        grid.selectSurface("surface-1");
        JSONObject wide = frame(true).put("columns", 6)
            .put("cursor", new JSONObject().put("row", 0).put("column", 3).put("visible", true))
            .put("row_spans", new JSONArray()
                .put(span(0, 0, "你a").put("grid_cell_width", 3))
                .put(span(0, 3, "x")));
        assertTrue(grid.apply(wide));
        TerminalSnapshot snapshot = grid.viewportSnapshot();
        assertEquals("你ax\n", snapshot.text);
        assertEquals(2, snapshot.cursorOffset);
    }

    private static JSONObject frame(boolean full) throws Exception {
        return new JSONObject()
            .put("format", "cmux.render-grid.v1")
            .put("surface_id", "surface-1")
            .put("state_seq", full ? 10 : 11)
            .put("columns", 20)
            .put("rows", 2)
            .put("full", full);
    }

    private static JSONObject span(int row, int column, String text) throws Exception {
        return new JSONObject().put("row", row).put("column", column).put("text", text)
            .put("grid_cell_width", text.codePointCount(0, text.length()));
    }
}
