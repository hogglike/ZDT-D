package ru.hogglike.watchassistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import com.google.android.gms.wearable.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class BridgeService extends Service {
    static volatile BridgeService current;
    private final Protocol.Gate gate = new Protocol.Gate();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String currentId = "", currentNode = "";
    private volatile boolean cancelled;
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Связь с часами", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, PhoneActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BridgeService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "bridge").setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("Hogg — мост Gemini").setContentText("Связь с часами включена · 0.2.1")
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Выключить", stop).build()).setOngoing(true).build();
        startForeground(1, n); current = this; state("Связь с часами включена");
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        current = null; cancelled = true; if (GeminiAccess.current != null) GeminiAccess.current.cancel(currentId); worker.shutdownNow();
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
            wake.acquire(110_000);
            try {
                if (data.containsKey("audio")) throw new IllegalStateException("Обнови оба APK Hogg до 0.2.1. Голос теперь распознаётся системным вводом часов.");
                text = data.getString("text", "").trim();
                if (text.isEmpty() || text.length() > Protocol.MAX_TEXT) throw new IllegalStateException("Нужен вопрос длиной до 3000 символов.");
                if (cancelled) throw new InterruptedException();
                GeminiAccess access = GeminiAccess.current;
                if (access == null) throw new IllegalStateException("На телефоне включи Hogg — мост Gemini в специальных возможностях Android.");
                CountDownLatch done = new CountDownLatch(1);
                String[] result = {"", ""};
                access.ask(id, text, new GeminiAccess.Callback() {
                    @Override public void progress(String message) { if (!cancelled && currentId.equals(id)) BridgeService.this.progress(node, id, message); }
                    @Override public void done(String value, String failure) { result[0] = value; result[1] = failure; done.countDown(); }
                });
                if (cancelled) access.cancel(id);
                if (!done.await(95, TimeUnit.SECONDS)) { access.cancel(id); throw new IllegalStateException("Ответ Gemini не получен вовремя. Автоматического повтора нет; проверь телефон и диагностику."); }
                if (cancelled) throw new InterruptedException();
                answer = result[0]; error = result[1];
                p.edit().putString("last_question", text).putString("last_answer", answer).apply();
            } catch (InterruptedException e) {
                error = "Запрос отменён. Уже отправленную команду Gemini отменить этим нельзя."; answer = "";
                if (GeminiAccess.current != null) GeminiAccess.current.cancel(id); Thread.currentThread().interrupt();
            } catch (Exception e) { error = e.getMessage() == null ? "Не удалось передать команду Gemini. Скопируй диагностику." : e.getMessage(); answer = ""; }
            finally {
                if (wake.isHeld()) wake.release();
                Wearable.getDataClient(this).deleteDataItems(uri);
                reply(id, node, text, answer, error); currentId = ""; currentNode = ""; gate.finish(id);
                state(error.isEmpty() ? "Ответ отправлен на часы" : error);
            }
        });
    }
    void cancel(String node, String id) { if (node.equals(currentNode) && id.equals(currentId)) { cancelled = true; if (GeminiAccess.current != null) GeminiAccess.current.cancel(id); } }
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
