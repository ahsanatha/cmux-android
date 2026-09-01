package dev.cmux.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class StateSyncMirrorTest {
    @Test public void snapshotsOverlapAndGapsMatchCmuxProtocol() throws Exception {
        StateSyncMirror mirror = new StateSyncMirror();
        JSONObject snapshot = new JSONObject().put("epoch", "e1")
            .put("workspaces", section("snapshot", 2, -1,
                new JSONArray().put(record("b", 1)).put(record("a", 0))))
            .put("groups", section("snapshot", 1, -1, new JSONArray()));
        assertEquals(StateSyncMirror.Result.APPLIED, mirror.applyFetch(snapshot));
        assertEquals("a", mirror.projection().getJSONArray("workspaces")
            .getJSONObject(0).getString("id"));

        JSONObject overlap = new JSONObject().put("epoch", "e1").put("collection", "workspaces")
            .put("from_rev", 1).put("to_rev", 3)
            .put("records", new JSONArray().put(record("c", 2)))
            .put("removed_ids", new JSONArray().put("a"));
        assertEquals(StateSyncMirror.Result.APPLIED, mirror.applyDelta(overlap));
        assertEquals(2, mirror.projection().getJSONArray("workspaces").length());

        JSONObject gap = new JSONObject(overlap.toString()).put("from_rev", 5).put("to_rev", 6);
        assertEquals(StateSyncMirror.Result.GAP, mirror.applyDelta(gap));
        assertEquals(2, mirror.projection().getJSONArray("workspaces").length());
    }

    @Test public void malformedFetchCannotLeaveMixedCollectionCursors() throws Exception {
        StateSyncMirror mirror = new StateSyncMirror();
        JSONObject response = new JSONObject().put("epoch", "e1")
            .put("workspaces", section("snapshot", 2, -1,
                new JSONArray().put(record("a", 0))))
            .put("groups", new JSONObject().put("mode", "broken").put("rev", 1));
        assertEquals(StateSyncMirror.Result.GAP, mirror.applyFetch(response));
        assertFalse(mirror.ready());
        assertFalse(mirror.fetchParams().getJSONArray("collections")
            .getJSONObject(0).has("epoch"));
    }

    private static JSONObject section(String mode, long rev, long from, JSONArray records)
        throws Exception {
        JSONObject value = new JSONObject().put("mode", mode).put("rev", rev)
            .put("records", records).put("removed_ids", new JSONArray());
        if (from >= 0) value.put("from_rev", from);
        return value;
    }

    private static JSONObject record(String id, int sort) throws Exception {
        return new JSONObject().put("id", id).put("sort_index", sort).put("title", id);
    }
}
