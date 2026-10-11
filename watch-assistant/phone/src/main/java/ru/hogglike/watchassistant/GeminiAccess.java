package ru.hogglike.watchassistant;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.os.*;
import android.speech.RecognizerIntent;
import android.view.accessibility.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** User-initiated Gemini UI bridge. It never unlocks the phone or confirms a Gemini action. */
public final class GeminiAccess extends AccessibilityService {
    static volatile GeminiAccess current;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Pending pending;
    private final Map<String, Runnable> scheduled = new HashMap<>();
    private final Map<String, PowerManager.WakeLock> scheduledWake = new HashMap<>();
    interface Callback { void progress(String message); void done(String answer, String error); }
    private static final class Pending {
        String id, question, baseline = "", candidate = "";
        long start, unchangedAt;
        int phase;
        boolean keyboardClicked;
        Callback callback;
        PowerManager.WakeLock wake;
    }
    private static final class Entry {
        AccessibilityNodeInfo node;
        String text, description, hint;
        boolean leaf;
    }
    private static final class Frame implements AutoCloseable {
        final List<Entry> entries = new ArrayList<>();
        final List<GeminiText.Line> lines = new ArrayList<>();
        String pkg = "";
        boolean gemini, generating;
        String plain() { StringBuilder b = new StringBuilder("\n"); for (GeminiText.Line l : lines) b.append(l.text).append('\n'); return b.toString(); }
        @Override public void close() { for (Entry e : entries) e.node.recycle(); }
    }
    @Override public void onServiceConnected() { current = this; }
    @Override public void onInterrupt() { cancelActive("Android прервал управление Gemini. Повтори запрос."); }
    @Override public void onDestroy() {
        cancelActive("Доступ к Gemini выключен."); current = null; main.removeCallbacksAndMessages(null);
        for (PowerManager.WakeLock wake : scheduledWake.values()) if (wake.isHeld()) wake.release();
        scheduledWake.clear(); scheduled.clear(); super.onDestroy();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pending != null || System.currentTimeMillis() < getSharedPreferences("bridge", MODE_PRIVATE).getLong("diagnostic_until", 0)) {
            main.removeCallbacks(tick); main.postDelayed(tick, 150);
        }
    }
    static boolean allowed(String pkg) { return "com.google.android.googlequicksearchbox".equals(pkg) || "com.google.android.apps.bard".equals(pkg); }
    private final Runnable tick = () -> {
        try (Frame f = frame()) { record(f); advance(f); }
        catch (Exception e) { if (pending != null) finish("", "Не удалось прочитать интерфейс Gemini. Скопируй диагностику на телефоне."); }
        if (pending != null) main.postDelayed(this.tick, 500);
    };
    void ask(String id, String question, Callback callback) {
        main.post(() -> {
            if (pending != null) { callback.done("", "Мост ещё обрабатывает предыдущий запрос."); return; }
            Pending p = new Pending(); p.id = id; p.question = question; p.start = SystemClock.elapsedRealtime(); p.callback = callback;
            p.wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hogg:gemini");
            p.wake.acquire(100_000); pending = p;
            callback.progress("Вызываю телефонный Gemini…");
            if (getSharedPreferences("bridge", MODE_PRIVATE).getBoolean("root_launch", false)) {
                new Thread(() -> {
                    String error = "";
                    try {
                        // Fixed wake/assistant keys only: no user text is interpolated into a shell.
                        java.lang.Process process = new ProcessBuilder("su", "-c", "input keyevent 224\ninput keyevent 219").redirectErrorStream(true).start();
                        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); error = "Root-вызов не завершился. Проверь разрешение в ReSukiSU."; }
                        else if (process.exitValue() != 0) error = "Root-вызов не разрешён. Проверь разрешение Hogg в ReSukiSU или отключи root-вызов.";
                    } catch (Exception e) { error = "Не удалось вызвать Gemini через root. Отключи root-вызов или проверь ReSukiSU."; }
                    final String result = error;
                    main.post(() -> { if (pending != p) return; if (!result.isEmpty()) finish("", result); else main.post(tick); });
                }, "gemini-launch").start();
            } else {
                try {
                    Intent launch = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE).setPackage("com.google.android.googlequicksearchbox")
                        .putExtra(RecognizerIntent.EXTRA_SECURE, getSystemService(KeyguardManager.class).isKeyguardLocked()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch); main.post(tick);
                } catch (Exception e) { finish("", "Android не разрешил вызов помощника. Попробуй включить root-вызов в Hogg."); }
            }
        });
    }
    void askLater(String id, String question, long delay, Callback callback) {
        main.post(() -> {
            if (pending != null || !scheduled.isEmpty()) { callback.done("", "Мост ещё обрабатывает предыдущий запрос."); return; }
            PowerManager.WakeLock wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hogg:scheduled-test");
            wake.acquire(delay + 5000); scheduledWake.put(id, wake);
            Runnable run = () -> { scheduled.remove(id); releaseScheduled(id); ask(id, question, callback); };
            scheduled.put(id, run); main.postDelayed(run, delay);
        });
    }
    void cancel(String id) { main.post(() -> {
        Runnable run = scheduled.remove(id); if (run != null) main.removeCallbacks(run);
        releaseScheduled(id);
        if (pending != null && pending.id.equals(id)) finish("", "Запрос отменён. Уже отправленную команду Gemini отменить этим нельзя.");
    }); }
    private void releaseScheduled(String id) {
        PowerManager.WakeLock wake = scheduledWake.remove(id); if (wake != null && wake.isHeld()) wake.release();
    }
    private void cancelActive(String error) { if (pending != null) finish("", error); }
    private void finish(String answer, String error) {
        Pending p = pending; if (p == null) return; pending = null; main.removeCallbacks(tick);
        if (p.wake != null && p.wake.isHeld()) p.wake.release();
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("last_phase", "phase=" + p.phase).apply();
        p.callback.done(answer, error);
    }
    private void advance(Frame f) {
        Pending p = pending; if (p == null) return;
        long elapsed = SystemClock.elapsedRealtime() - p.start;
        if (elapsed > 90_000) { finish("", "Ответ Gemini не распознан. Команда могла быть отправлена; повтор автоматически не выполняется. Скопируй диагностику."); return; }
        if (!f.gemini) {
            if (elapsed > 18_000 && p.phase == 0) finish("", "Не найден интерфейс Gemini поверх блокировки. Включи запись диагностики и повтори один тест.");
            return;
        }
        if (p.phase == 0) {
            List<Entry> editors = new ArrayList<>();
            for (Entry e : f.entries) if (e.node.isEditable() && e.node.isVisibleToUser() && e.node.isEnabled()) editors.add(e);
            if (editors.size() == 1) {
                Entry editor = editors.get(0);
                if (!editor.text.trim().isEmpty()) { finish("", "В Gemini уже есть неотправленный текст. Мост не заменяет его: очисти поле вручную."); return; }
                p.baseline = f.plain();
                Bundle b = new Bundle(); b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.question);
                if (!editor.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) { finish("", "Gemini не разрешил ввод текста. Запиши диагностику интерфейса."); return; }
                p.phase = 1; p.callback.progress("Передаю команду в Gemini…"); return;
            }
            if (!p.keyboardClicked) for (Entry e : f.entries) if (GeminiText.keyboard(e.description) || GeminiText.keyboard(e.text)) {
                if (click(e.node)) { p.keyboardClicked = true; return; }
            }
            for (GeminiText.Line l : f.lines) if (GeminiText.unlock(l.text)) { finish("", "Gemini требует разблокировать телефон для ввода команды. Этот вариант моста на текущем экране не сработал."); return; }
            if (elapsed > 18_000) finish("", "У Gemini не найдено доступное поле ввода при блокировке. Нужна диагностика твоего интерфейса.");
        } else if (p.phase == 1) {
            boolean exact = false;
            for (Entry e : f.entries) if (e.node.isEditable() && e.text.equals(p.question)) exact = true;
            if (!exact) { if (elapsed > 24_000) finish("", "Gemini не подтвердил введённый текст. Запрос не отправлялся повторно."); return; }
            List<Entry> buttons = new ArrayList<>();
            for (Entry e : f.entries) if (e.node.isVisibleToUser() && e.node.isEnabled() && (GeminiText.send(e.text) || GeminiText.send(e.description))) buttons.add(e);
            if (buttons.size() == 1) {
                // Mark before click; an ambiguous UI event must never cause a second submission.
                p.phase = 2;
                if (!click(buttons.get(0).node)) { finish("", "Gemini не принял нажатие отправки. Автоматического повтора нет."); return; }
                p.callback.progress("Команда отправлена. Ожидаю ответ Gemini…");
            } else if (elapsed > 24_000) finish("", "Не удалось однозначно найти кнопку отправки Gemini. Нужна диагностика.");
        } else {
            String answer = GeminiText.answer(f.lines, p.question, "", f.generating);
            if (!answer.isEmpty() && p.baseline.contains("\n" + answer + "\n")) answer = "";
            if (!answer.equals(p.candidate)) { p.candidate = answer; p.unchangedAt = SystemClock.elapsedRealtime(); }
            if (!answer.isEmpty() && SystemClock.elapsedRealtime() - p.unchangedAt >= 3000) finish(answer, "");
        }
    }
    private boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; n != null && depth < 3; depth++) {
                if (!allowed(str(n.getPackageName()))) return false;
                if (n.isClickable() && n.isEnabled()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo parent = n.getParent(); n.recycle(); n = parent;
            }
            return false;
        } finally { if (n != null) n.recycle(); }
    }
    private Frame frame() {
        Frame f = new Frame(); AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) { if (allowed(str(active.getPackageName()))) visit(active, f, 0); else active.recycle(); }
        if (f.entries.isEmpty()) for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root != null) { if (allowed(str(root.getPackageName())) && f.entries.isEmpty()) visit(root, f, 0); else root.recycle(); }
            w.recycle();
        }
        f.gemini = "com.google.android.apps.bard".equals(f.pkg);
        for (Entry e : f.entries) {
            for (String value : new String[]{e.text, e.description, e.hint}) { String n = GeminiText.norm(value); if (n.equals("gemini") || n.startsWith("спросите gemini") || n.startsWith("ask gemini") || n.equals("gemini live")) f.gemini = true; }
            if (!e.node.isVisibleToUser()) continue;
            if (GeminiText.generating(e.text) || GeminiText.generating(e.description) || str(e.node.getClassName()).endsWith("ProgressBar")) f.generating = true;
            if (e.leaf || e.node.isEditable() || GeminiText.copy(e.text) || GeminiText.copy(e.description)) {
                String value = !e.text.isEmpty() ? e.text : e.description;
                f.lines.add(new GeminiText.Line(value, e.node.isEditable(), e.node.isClickable(), GeminiText.copy(e.text) || GeminiText.copy(e.description)));
            }
        }
        return f;
    }
    private void visit(AccessibilityNodeInfo n, Frame f, int depth) {
        if (depth > 35 || f.entries.size() >= 450 || !allowed(str(n.getPackageName()))) { n.recycle(); return; }
        Entry e = new Entry(); e.node = n; e.text = str(n.getText()); e.description = str(n.getContentDescription()); e.hint = str(n.getHintText());
        e.leaf = n.getChildCount() == 0; f.entries.add(e); f.pkg = str(n.getPackageName());
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo child = n.getChild(i); if (child != null) visit(child, f, depth + 1); }
    }
    private static String str(CharSequence s) { return s == null ? "" : s.toString(); }
    /** Opt-in short recording only. Full Gemini text stays local until the user copies the report. */
    private void record(Frame f) {
        SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        boolean recording = System.currentTimeMillis() < p.getLong("diagnostic_until", 0);
        try {
            JSONObject meta = new JSONObject().put("package", f.pkg).put("gemini_marker", f.gemini).put("nodes", f.entries.size())
                .put("locked", getSystemService(KeyguardManager.class).isKeyguardLocked()).put("screen_on", getSystemService(PowerManager.class).isInteractive())
                .put("phase", pending == null ? -1 : pending.phase);
            p.edit().putString("last_ui_status", meta.toString()).apply();
            if (!recording || !f.gemini) return;
            JSONArray nodes = new JSONArray();
            for (Entry e : f.entries) if (e.node.isVisibleToUser()) nodes.put(new JSONObject().put("id", str(e.node.getViewIdResourceName())).put("class", str(e.node.getClassName()))
                .put("text", Protocol.bounded(e.text, 1500)).put("description", Protocol.bounded(e.description, 300)).put("hint", Protocol.bounded(e.hint, 300))
                .put("editable", e.node.isEditable()).put("clickable", e.node.isClickable()));
            String fingerprint = nodes.toString(); if (fingerprint.equals(p.getString("diagnostic_last", ""))) return;
            JSONArray frames = new JSONArray(p.getString("diagnostic_frames", "[]"));
            if (frames.length() >= 12 || p.getString("diagnostic_frames", "[]").length() > 100_000) return;
            meta.put("time", System.currentTimeMillis()).put("ui", nodes); frames.put(meta);
            p.edit().putString("diagnostic_last", fingerprint).putString("diagnostic_frames", frames.toString()).apply();
        } catch (Exception ignored) { }
    }
}
