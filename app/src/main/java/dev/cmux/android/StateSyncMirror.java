package dev.cmux.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

final class StateSyncMirror {
    enum Result { APPLIED, STALE, GAP }

    private final Collection workspaces = new Collection();
    private final Collection groups = new Collection();

    synchronized JSONObject fetchParams() throws Exception {
        return new JSONObject().put("collections", new JSONArray()
            .put(workspaces.cursor("workspaces"))
            .put(groups.cursor("groups")));
    }

    synchronized Result applyFetch(JSONObject response) {
        String epoch = response.optString("epoch");
        JSONObject workspacePayload = response.optJSONObject("workspaces");
        JSONObject groupPayload = response.optJSONObject("groups");
        if (epoch.isBlank() || workspacePayload == null || groupPayload == null) return Result.GAP;
        Result workspaceResult = workspaces.applyPayload(epoch, workspacePayload);
        Result groupResult = groups.applyPayload(epoch, groupPayload);
        if (workspaceResult == Result.GAP || groupResult == Result.GAP) {
            reset();
            return Result.GAP;
        }
        return workspaceResult == Result.APPLIED || groupResult == Result.APPLIED
            ? Result.APPLIED : Result.STALE;
    }

    synchronized Result applyDelta(JSONObject delta) {
        String collection = delta.optString("collection");
        if ("workspaces".equals(collection)) return workspaces.applyDelta(delta);
        if ("groups".equals(collection)) return groups.applyDelta(delta);
        return Result.STALE;
    }

    synchronized boolean ready() { return workspaces.ready() && groups.ready(); }

    synchronized JSONObject projection() throws Exception {
        if (!ready()) throw new IllegalStateException("State sync is not ready");
        return new JSONObject()
            .put("workspaces", workspaces.records())
            .put("groups", groups.records());
    }

    synchronized void reset() {
        workspaces.reset();
        groups.reset();
    }

    private static final class Collection {
        private String epoch;
        private long revision;
        private final Map<String, JSONObject> byId = new HashMap<>();

        boolean ready() { return epoch != null; }

        JSONObject cursor(String id) throws Exception {
            JSONObject value = new JSONObject().put("id", id);
            if (epoch != null) value.put("epoch", epoch).put("rev", revision);
            return value;
        }

        Result applyPayload(String payloadEpoch, JSONObject payload) {
            String mode = payload.optString("mode");
            long nextRevision = payload.optLong("rev", -1);
            if (nextRevision < 0) return Result.GAP;
            if ("snapshot".equals(mode)) {
                if (payloadEpoch.equals(epoch) && nextRevision < revision) return Result.STALE;
                Map<String, JSONObject> replacement = decodeRecords(payload.optJSONArray("records"));
                if (replacement == null) return Result.GAP;
                byId.clear();
                byId.putAll(replacement);
                epoch = payloadEpoch;
                revision = nextRevision;
                return Result.APPLIED;
            }
            if (!"delta".equals(mode)) return Result.GAP;
            return applyChanges(payloadEpoch, payload.optLong("from_rev", nextRevision),
                nextRevision, payload.optJSONArray("records"), payload.optJSONArray("removed_ids"));
        }

        Result applyDelta(JSONObject delta) {
            return applyChanges(delta.optString("epoch"), delta.optLong("from_rev", -1),
                delta.optLong("to_rev", -1), delta.optJSONArray("records"),
                delta.optJSONArray("removed_ids"));
        }

        private Result applyChanges(String nextEpoch, long fromRevision, long toRevision,
                                    JSONArray records, JSONArray removed) {
            if (epoch == null || !epoch.equals(nextEpoch) || fromRevision < 0 || toRevision < 0
                || fromRevision > revision) return Result.GAP;
            if (toRevision <= revision) return Result.STALE;
            Map<String, JSONObject> decoded = decodeRecords(records);
            if (decoded == null) return Result.GAP;
            if (removed != null) for (int i = 0; i < removed.length(); i++) {
                String id = removed.optString(i);
                if (!id.isBlank()) byId.remove(id);
            }
            byId.putAll(decoded);
            revision = toRevision;
            return Result.APPLIED;
        }

        private static Map<String, JSONObject> decodeRecords(JSONArray values) {
            if (values == null) return null;
            Map<String, JSONObject> decoded = new HashMap<>();
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null || value.optString("id").isBlank()
                    || value.optInt("sort_index", -1) < 0) return null;
                try {
                    decoded.put(value.optString("id"), new JSONObject(value.toString()));
                } catch (Exception invalid) {
                    return null;
                }
            }
            return decoded;
        }

        JSONArray records() throws Exception {
            ArrayList<JSONObject> sorted = new ArrayList<>(byId.values());
            sorted.sort(Comparator.<JSONObject>comparingInt(value -> value.optInt("sort_index"))
                .thenComparing(value -> value.optString("id")));
            JSONArray result = new JSONArray();
            for (JSONObject value : sorted) {
                JSONObject copy = new JSONObject(value.toString());
                copy.remove("sort_index");
                result.put(copy);
            }
            return result;
        }

        void reset() {
            epoch = null;
            revision = 0;
            byId.clear();
        }
    }
}
