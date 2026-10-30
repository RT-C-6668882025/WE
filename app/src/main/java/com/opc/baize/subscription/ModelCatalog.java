package com.opc.baize.subscription;

import org.json.*;
import java.util.*;
import static com.opc.baize.subscription.SubscriptionProtocol.*;

/** Account-supplied models only. Availability is not a claim of image support. */
public final class ModelCatalog {
    public static final class Entry {
        public final String id, name;
        Entry(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name.equals(id) ? id : name + " · " + id; }
    }
    public static List<Entry> parse(JSONObject response) throws Exception {
        JSONArray models = response.optJSONArray("models");
        if (models == null) throw new Failure("invalid_model_catalog");
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null || !"list".equals(model.optString("visibility"))) continue;
            String id = model.optString("slug", "").trim();
            String name = model.optString("display_name", id).trim();
            if (id.isEmpty() || id.length() > 200 || name.isEmpty() || name.length() > 200
                || id.matches(".*[\\p{Cntrl}].*") || name.matches(".*[\\p{Cntrl}].*")) throw new Failure("invalid_model_catalog");
            result.putIfAbsent(id, new Entry(id, name));
        }
        if (result.isEmpty()) throw new Failure("no_visible_models");
        return Collections.unmodifiableList(new ArrayList<>(result.values()));
    }
    public static boolean contains(List<Entry> catalog, String id) {
        for (Entry entry : catalog) if (entry.id.equals(id)) return true;
        return false;
    }
}
