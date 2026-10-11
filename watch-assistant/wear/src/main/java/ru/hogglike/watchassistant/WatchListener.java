package ru.hogglike.watchassistant;

import android.content.Context;
import com.google.android.gms.wearable.*;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WatchListener extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) if (e.getType() == DataEvent.TYPE_CHANGED && e.getDataItem().getUri().getPath().startsWith(Protocol.ANSWER)) save(this, DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
    }
    static synchronized boolean save(Context c, DataMap d) {
        android.content.SharedPreferences p = c.getSharedPreferences("watch", MODE_PRIVATE);
        String id = d.getString("id", "");
        if (!id.equals(p.getString("request", "")) || id.equals(p.getString("completed", ""))) return false;
        String answer = Protocol.bounded(d.getString("answer", ""), Protocol.MAX_ANSWER);
        String question = Protocol.bounded(d.getString("question", ""), Protocol.MAX_TEXT);
        String error = Protocol.bounded(d.getString("error", ""), 1000);
        android.content.SharedPreferences.Editor ed = p.edit().putString("completed", id).putString("answer", answer).putString("question", question).putString("error", error).putBoolean("busy", false);
        if (!answer.isEmpty()) {
            try {
                JSONArray history = new JSONArray(p.getString("history", "[]"));
                history.put(new JSONObject().put("question", question).put("answer", answer));
                JSONArray bounded = new JSONArray(); for (int i = Math.max(0, history.length() - 8); i < history.length(); i++) bounded.put(history.get(i));
                ed.putString("history", bounded.toString());
            } catch (Exception ignored) { }
        }
        return ed.commit();
    }
}
