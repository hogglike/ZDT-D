package ru.hogglike.watchassistant;
import com.google.android.gms.wearable.*;
import java.nio.charset.StandardCharsets;
public final class PhoneListener extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent e : events) {
            if (e.getType() != DataEvent.TYPE_CHANGED || !e.getDataItem().getUri().getPath().startsWith(Protocol.REQUEST)) continue;
            DataMap d = DataMapItem.fromDataItem(e.getDataItem()).getDataMap();
            BridgeService s = BridgeService.current;
            if (s != null) s.accept(e.getDataItem().getUri(), d);
            else {
                BridgeService.reply(this, d.getString("id", ""), e.getDataItem().getUri().getHost(), "", "", "На телефоне открой Hogg и включи связь с часами.");
                Wearable.getDataClient(this).deleteDataItems(e.getDataItem().getUri());
            }
        }
    }
    @Override public void onMessageReceived(MessageEvent e) {
        if (e.getPath().equals(Protocol.PING)) {
            String message = BridgeService.current == null ? "На телефоне открой Hogg и включи связь с часами." : GeminiAccess.current != null ? "Готов" : "Включи Hogg — мост Gemini в специальных возможностях телефона.";
            Wearable.getMessageClient(this).sendMessage(e.getSourceNodeId(), Protocol.PONG, message.getBytes(StandardCharsets.UTF_8));
        } else if (e.getPath().equals(Protocol.CANCEL)) {
            String id = new String(e.getData(), StandardCharsets.UTF_8);
            if (!Protocol.validId(id)) return;
            getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("cancelled_id", e.getSourceNodeId() + ":" + id).commit();
            if (BridgeService.current != null) BridgeService.current.cancel(e.getSourceNodeId(), id);
        }
    }
}
