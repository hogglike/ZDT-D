package ru.hogglike.watchassistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.*;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class BridgeService extends Service {
    static volatile BridgeService current;
    private final Protocol.Gate gate = new Protocol.Gate();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile GroqClient client;
    private volatile String currentId = "", currentNode = "";
    private volatile boolean cancelled;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Связь с часами", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, PhoneActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "bridge").setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("Hogg — помощник часов").setContentText("Готов принимать вопросы с часов")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Выключить", stop).build()).setOngoing(true).build();
        startForeground(1, n); current = this; state("Связь с часами включена");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        current = null; cancelled = true; if (client != null) client.cancel(); worker.shutdownNow();
        state("Связь выключена. Для вопросов с часов включи её снова."); super.onDestroy();
    }
    void accept(Uri uri, DataMap data) {
        String id = data.getString("id", ""), node = uri.getHost();
        if (node == null || !uri.getPath().equals(Protocol.REQUEST + id) || !Protocol.validId(data.getString("conversation", ""))) return;
        if ((node + ":" + id).equals(getSharedPreferences("bridge", MODE_PRIVATE).getString("cancelled_id", ""))) {
            Wearable.getDataClient(this).deleteDataItems(uri); return;
        }
        String decision = gate.accept(id, data.getLong("created"), System.currentTimeMillis());
        if (decision.equals("duplicate")) return;
        if (!decision.equals("ok")) {
            reply(id, node, "", "", decision.equals("busy") ? "Телефон ещё отвечает на предыдущий вопрос." : "Запрос устарел. Отправь вопрос заново.");
            Wearable.getDataClient(this).deleteDataItems(uri); return;
        }
        SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        if (id.equals(p.getString("last_id", ""))) { gate.finish(id); reply(id, node, "", "", "Этот запрос уже был принят. Отправь вопрос заново."); Wearable.getDataClient(this).deleteDataItems(uri); return; }
        p.edit().putString("last_id", id).commit();
        currentId = id; currentNode = node; cancelled = false;
        worker.execute(() -> {
            String text = "", answer = "", error = "";
            PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hogg:question");
            wake.acquire(130_000);
            try {
                KeyStoreSettings settings = new KeyStoreSettings(this);
                client = new GroqClient(settings.readKey(), settings.model());
                if (cancelled) throw new IOException("Запрос отменён.");
                if (data.containsKey("audio")) {
                    progress(node, id, "Распознаю русский вопрос…");
                    DataClient.GetFdForAssetResponse fd = Tasks.await(Wearable.getDataClient(this).getFdForAsset(data.getAsset("audio")), 20, TimeUnit.SECONDS);
                    byte[] audio;
                    try (InputStream in = fd.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        if (in == null) throw new IOException("Запись не получена.");
                        byte[] b = new byte[4096]; int n;
                        while ((n = in.read(b)) != -1) { if (cancelled) throw new IOException("Запрос отменён."); if (out.size() + n > Protocol.MAX_AUDIO) throw new IOException("Запись слишком большая."); out.write(b, 0, n); }
                        audio = out.toByteArray();
                    } finally { fd.release(); }
                    text = client.transcribe(audio);
                } else text = Protocol.bounded(data.getString("text", ""), Protocol.MAX_TEXT);
                if (cancelled) throw new IOException("Запрос отменён.");
                progress(node, id, "Думаю…");
                String conversation = node + ":" + data.getString("conversation");
                JSONArray history = new JSONArray(p.getString("conversation", "").equals(conversation) ? p.getString("history", "[]") : "[]");
                answer = client.answer(text, history);
                if (cancelled) throw new IOException("Запрос отменён.");
                history.put(new org.json.JSONObject().put("role", "user").put("content", text));
                history.put(new org.json.JSONObject().put("role", "assistant").put("content", answer));
                JSONArray bounded = new JSONArray(); for (int i = Math.max(0, history.length() - 6); i < history.length(); i++) bounded.put(history.get(i));
                p.edit().putString("conversation", conversation).putString("history", bounded.toString()).putString("last_question", text).putString("last_answer", answer).apply();
            } catch (Exception e) { error = cancelled ? "Запрос отменён." : GroqClient.readable(e); answer = ""; }
            finally {
                if (wake.isHeld()) wake.release(); client = null;
                Wearable.getDataClient(this).deleteDataItems(uri);
                reply(id, node, text, answer, error); currentId = ""; currentNode = ""; gate.finish(id);
                state(error.isEmpty() ? "Ответ отправлен на часы" : error);
            }
        });
    }
    void cancel(String node, String id) { if (node.equals(currentNode) && id.equals(currentId)) { cancelled = true; if (client != null) client.cancel(); } }
    static void reply(Context c, String id, String node, String text, String answer, String error) {
        if (!Protocol.validId(id)) return;
        PutDataMapRequest r = PutDataMapRequest.create(Protocol.ANSWER + node);
        r.getDataMap().putString("id", id); r.getDataMap().putString("target", node);
        r.getDataMap().putString("question", text); r.getDataMap().putString("answer", answer); r.getDataMap().putString("error", error);
        r.getDataMap().putLong("time", System.currentTimeMillis());
        Wearable.getDataClient(c).putDataItem(r.asPutDataRequest().setUrgent());
    }
    private void reply(String id, String node, String text, String answer, String error) { reply(this, id, node, text, answer, error); }
    private void progress(String node, String id, String value) {
        Wearable.getMessageClient(this).sendMessage(node, Protocol.PROGRESS, (id + "\n" + value).getBytes(StandardCharsets.UTF_8)); state(value);
    }
    private void state(String text) { getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("state", text).apply(); }
}
