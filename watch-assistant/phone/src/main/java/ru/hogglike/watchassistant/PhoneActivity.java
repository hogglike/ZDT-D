package ru.hogglike.watchassistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.android.gms.wearable.*;
import org.json.*;
import java.util.UUID;

public final class PhoneActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status, result, devices;
    private EditText question;
    private SharedPreferences prefs;
    private boolean wantsStart;
    private final Runnable refresh = new Runnable() { public void run() {
        status.setText((BridgeService.current == null ? "Связь с часами выключена" : prefs.getString("state", "Связь включена"))
            + "\nДоступ к Gemini: " + (GeminiAccess.current == null ? "выключен" : "включён"));
        if (prefs.contains("test_result")) result.setText(prefs.getString("test_result", ""));
        main.postDelayed(this, 2000);
    }};
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(246,248,245)); getWindow().setNavigationBarColor(Color.rgb(246,248,245));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(30), dp(22), dp(36)); content.setBackgroundColor(Color.rgb(246,248,245)); scroll.addView(content); setContentView(scroll);
        text("Hogg — мост Gemini", 26); text("0.2.0 · экспериментальная версия", 14);
        text("Команда с часов вводится в телефонный Gemini. Его ответ возвращается на часы. Используется твой аккаунт Gemini, ключ не нужен. Работа моста зависит от интерфейса твоей версии Google/Gemini.", 17);
        status = text("", 18); status.setTextColor(Color.rgb(44,102,87));
        text("1. Доступ к телефонному Gemini", 22);
        button("Включить доступ к Gemini", () -> new AlertDialog.Builder(this).setTitle("Доступ через спецвозможности")
            .setMessage("Hogg сможет читать интерфейс Google/Gemini, вводить твой вопрос и нажимать отправку по твоей команде. Мост не разблокирует телефон и не нажимает подтверждения действий Gemini. Полный текст интерфейса записывается только при отдельном включении диагностики на 60 секунд. Доступ включается тобой в настройках Android.")
            .setPositiveButton("Открыть настройки", (d,w) -> {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }).setNegativeButton("Отмена", null).show());
        text("При установке APK Android может заблокировать спецвозможности. В настройках приложения Hogg нажми меню ⋮ → «Разрешить ограниченные настройки», затем включи сервис. Настрой Gemini помощником по умолчанию и разреши его использование на экране блокировки.", 15);
        CheckBox root = new CheckBox(this); root.setText("Вызов помощника через root"); root.setChecked(prefs.getBoolean("root_launch", false)); content.addView(root);
        root.setOnCheckedChangeListener((v,on) -> prefs.edit().putBoolean("root_launch", on).apply());
        text("Если обычный вызов не открывает Gemini, включи root-вызов и разреши Hogg в ReSukiSU. Он посылает только команды включения экрана и вызова помощника. Блокировка сохраняется. Первый запрос root нужно разрешить при разблокированном телефоне.", 15);
        button("Открыть Gemini", () -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard");
            try { startActivity(i != null ? i : new Intent(Intent.ACTION_ASSIST)); }
            catch (Exception e) { result.setText("Не удалось открыть Gemini. Проверь его установку и помощника по умолчанию."); }
        });
        text("2. Связь с часами", 22);
        button("Включить связь с часами", this::enable);
        button("Выключить связь", () -> stopService(new Intent(this, BridgeService.class)));
        text("Установи второй APK на часы. Телефон и часы должны быть сопряжены через Galaxy Wearable. Пока связь включена, на телефоне показывается уведомление Hogg.", 16);
        devices = text("Часы ещё не проверены", 16);
        button("Найти часы", () -> Wearable.getCapabilityClient(this).getCapability("hogg_ai_watch", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(cap -> { StringBuilder names = new StringBuilder(); for (Node n : cap.getNodes()) names.append(n.getDisplayName()).append("\n"); devices.setText(names.length() == 0 ? "Hogg на часах не найден. Проверь установку и Bluetooth." : "Доступны:\n" + names); })
            .addOnFailureListener(e -> devices.setText("Не удалось проверить часы. Проверь сервисы Google Play и Bluetooth.")));
        text("3. Проверка моста", 22);
        question = new EditText(this); question.setTextSize(16); question.setHint("Тестовый вопрос"); question.setText("Почему небо голубое? Ответь коротко на русском."); question.setMaxLines(4); content.addView(question);
        button("Тест сейчас", () -> test(0));
        button("Тест через 10 секунд — успей заблокировать телефон", () -> test(10_000));
        button("Отменить тест", () -> {
            String id = prefs.getString("test_id", ""); if (GeminiAccess.current != null) GeminiAccess.current.cancel(id);
            prefs.edit().putString("test_id", "").putString("test_result", "Тест отменён. Уже отправленную команду Gemini отменить этим нельзя.").apply();
        });
        result = text(prefs.getString("test_result", "Сначала проверь мост на телефоне. Если ответ пришёл, задай вопрос с часов."), 17); result.setTextIsSelectable(true);
        text("Телефон может включить экран, оставаясь заблокированным. Если поле ввода или ответ не распознаны, отправь диагностику. Не используй опасные команды для первого теста. Диалог ведётся в текущем чате Gemini на телефоне.", 15);
        text("4. Диагностика интерфейса", 22);
        button("Записать интерфейс Gemini · 60 секунд", () -> new AlertDialog.Builder(this).setTitle("Запись только на телефоне")
            .setMessage("В запись может попасть текст видимого чата Gemini. Используй несекретный тестовый вопрос. Запись длится 60 секунд; автоматически никуда не отправляется. Затем можно скопировать отчёт и самостоятельно отправить его для исправления моста.")
            .setPositiveButton("Начать", (d,w) -> {
                if (GeminiAccess.current == null) { result.setText("Сначала включи доступ к Gemini."); return; }
                prefs.edit().putLong("diagnostic_until", System.currentTimeMillis()+60_000).putString("diagnostic_frames", "[]").remove("diagnostic_last").apply();
                result.setText("Запись включена на 60 секунд. Теперь запусти один тест при блокировке. После него скопируй диагностику.");
                prefs.edit().remove("test_result").apply();
            }).setNegativeButton("Отмена", null).show());
        button("Скопировать диагностику", this::copyReport);
        button("Удалить диагностику", () -> { prefs.edit().remove("diagnostic_frames").remove("diagnostic_last").putLong("diagnostic_until",0).apply(); result.setText("Запись удалена"); prefs.edit().remove("test_result").apply(); });
        text("Интернет и фоновая работа", 22);
        text("Интернет нужен телефонным Google/Gemini. Сохрани их рабочие назначения в ZDT-D. Мост не меняет DNS, VPN, раздачу и подписки. Проверка телефона на часах проверяет связь и включение сервиса, но не ответ Gemini. Если OnePlus останавливает Hogg, разреши ему фоновую работу в настройках батареи.", 16);
        button("Настройки приложения Android", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }
    private void test(long delay) {
        if (!prefs.getString("test_id", "").isEmpty()) { result.setText("Предыдущий тест ещё не завершён. Подожди или нажми «Отменить тест»."); return; }
        GeminiAccess access = GeminiAccess.current;
        if (access == null) { result.setText("Сначала включи доступ к Gemini в спецвозможностях."); return; }
        if (BridgeService.current == null) { result.setText("Сначала включи связь с часами для работы в фоне."); return; }
        String q = question.getText().toString().trim();
        if (q.isEmpty() || q.length() > Protocol.MAX_TEXT) { result.setText("Введи вопрос длиной до 3000 символов."); return; }
        String id = UUID.randomUUID().toString();
        prefs.edit().putString("test_id", id).putString("test_result", delay == 0 ? "Начинаю тест…" : "Тест через 10 секунд. Заблокируй телефон.").apply();
        access.askLater(id, q, delay, new GeminiAccess.Callback() {
            @Override public void progress(String message) { if (prefs.getString("test_id", "").equals(id)) prefs.edit().putString("test_result",message).apply(); }
            @Override public void done(String answer, String error) { if (prefs.getString("test_id", "").equals(id)) prefs.edit().putString("test_result", error.isEmpty() ? answer : error).putString("test_id", "").apply(); }
        });
    }
    private void copyReport() {
        try {
            JSONObject r = new JSONObject().put("version",BuildConfig.VERSION_NAME).put("model", Build.MANUFACTURER+" "+Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                .put("bridge", BridgeService.current != null).put("accessibility",GeminiAccess.current != null).put("root_launch",prefs.getBoolean("root_launch",false))
                .put("assistant",Settings.Secure.getString(getContentResolver(),"assistant")).put("voice_service",Settings.Secure.getString(getContentResolver(),"voice_interaction_service"))
                .put("last_phase",prefs.getString("last_phase", "")).put("test_result",prefs.getString("test_result", ""))
                .put("ui_status",new JSONObject(prefs.getString("last_ui_status", "{}"))).put("frames",new JSONArray(prefs.getString("diagnostic_frames","[]")));
            for (String pkg : new String[]{"com.google.android.apps.bard", "com.google.android.googlequicksearchbox"}) {
                try { r.put(pkg, getPackageManager().getPackageInfo(pkg,0).versionName); } catch (PackageManager.NameNotFoundException e) { r.put(pkg,"not installed"); }
            }
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Hogg Gemini bridge",r.toString(2)));
            result.setText("Диагностика скопирована. Проверь её текст перед отправкой: при включённой записи там есть содержимое интерфейса Gemini.");
            prefs.edit().remove("test_result").apply();
        } catch (Exception e) { result.setText("Не удалось собрать диагностику."); }
    }
    private void enable() {
        wantsStart = true;
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 2); else startBridge();
    }
    private void startBridge() {
        wantsStart = false;
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { result.setText("Для фоновой связи с часами разреши доступ к устройствам поблизости."); return; }
        try { startForegroundService(new Intent(this, BridgeService.class)); status.setText("Включаю связь…"); }
        catch (Exception e) { result.setText("Android не разрешил запуск фоновой связи. Проверь разрешения приложения."); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) { super.onRequestPermissionsResult(code,p,r); if (code == 2 && wantsStart) startBridge(); }
    @Override public void onResume() { super.onResume(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override public void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n); }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(32,45,38)); t.setPadding(0, dp(10), 0, dp(10)); content.addView(t); return t; }
    private void button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(44,102,87)); bg.setCornerRadius(dp(16)); b.setBackground(bg); b.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(7),0,dp(7)); content.addView(b,p); b.setOnClickListener(v -> action.run());
    }
}
