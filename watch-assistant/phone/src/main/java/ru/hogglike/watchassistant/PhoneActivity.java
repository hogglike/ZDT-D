package ru.hogglike.watchassistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.android.gms.wearable.*;
import org.json.JSONArray;

public final class PhoneActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status, result, devices;
    private EditText secret, model, question;
    private KeyStoreSettings settings;
    private boolean wantsStart, testing;
    private GroqClient testClient;
    private final Runnable refresh = new Runnable() { public void run() {
        if (status != null) status.setText(BridgeService.current == null ? "Связь с часами выключена" : getSharedPreferences("bridge", MODE_PRIVATE).getString("state", "Связь включена"));
        main.postDelayed(this, 2000);
    }};
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); settings = new KeyStoreSettings(this);
        getWindow().setStatusBarColor(Color.rgb(246,248,245)); getWindow().setNavigationBarColor(Color.rgb(246,248,245));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(30), dp(22), dp(36)); content.setBackgroundColor(Color.rgb(246,248,245)); scroll.addView(content); setContentView(scroll);
        text("Hogg — помощник часов", 26); text("Версия 0.1.0 · Galaxy Watch / Wear OS", 14);
        text("Говори на часах — телефон распознает вопрос и вернёт ответ. Меню и ответы — на русском.", 17);
        status = text("Связь выключена", 18); status.setTextColor(Color.rgb(44,102,87));
        button("Включить связь с часами", () -> { if (save()) enable(); });
        button("Выключить связь", () -> { stopService(new Intent(this, BridgeService.class)); status.setText("Связь выключена"); });
        text("1. Бесплатный ключ Groq", 22);
        text("Создай ключ в своём аккаунте Groq на бесплатном тарифе. Карта и платный тариф для приложения не нужны. Бесплатный тариф имеет лимиты. Записи вопросов и текст отправляются в Groq для обработки.", 16);
        button("Открыть страницу ключей Groq", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))));
        secret = edit(settings.hasKey() ? "Ключ сохранён — поле можно оставить пустым" : "Вставь ключ Groq", "");
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        text("Ключ зашифрован на телефоне. На часы он не передаётся.", 14);
        model = edit("Модель ИИ", settings.model());
        text("Оставь модель по умолчанию. Если сервис перестанет её поддерживать, здесь можно указать другую модель бесплатного тарифа.", 14);
        button("Сохранить настройки", this::save);
        button("Удалить ключ", () -> new AlertDialog.Builder(this).setMessage("Удалить ключ и выключить связь с часами?")
            .setPositiveButton("Удалить", (d,w) -> { stopService(new Intent(this, BridgeService.class)); try { settings.saveKey(""); secret.setText(""); secret.setHint("Вставь ключ Groq"); result.setText("Ключ удалён"); } catch (Exception e) { result.setText("Не удалось удалить ключ"); } })
            .setNegativeButton("Отмена", null).show());
        text("2. Проверка на телефоне", 22);
        question = edit("Тестовый вопрос", "Скажи короткое приветствие на русском языке.");
        button("Проверить ИИ", () -> {
            if (testing || !save()) return;
            testing = true; String q = question.getText().toString(); result.setText("Проверяю подключение к Groq…");
            new Thread(() -> {
                String answer;
                try { testClient = new GroqClient(settings.readKey(), settings.model()); answer = testClient.answer(q, new JSONArray()); }
                catch (Exception e) { answer = GroqClient.readable(e); }
                final String value = answer; main.post(() -> { testing = false; if (!isDestroyed()) result.setText(value); });
            }, "hogg-phone-test").start();
        });
        result = text("Сначала проверь ответ здесь, затем переходи к часам.", 17); result.setTextIsSelectable(true);
        text("3. Подключение часов", 22);
        text("Установи APK для часов отдельно. Телефон и часы должны быть сопряжены через Galaxy Wearable. На телефоне включи связь кнопкой выше; на часах открой Hogg и нажми «Проверить телефон».", 16);
        devices = text("Часы ещё не проверены", 16);
        button("Найти часы", () -> Wearable.getCapabilityClient(this).getCapability("hogg_ai_watch", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(cap -> { StringBuilder names = new StringBuilder(); for (Node n : cap.getNodes()) names.append(n.getDisplayName()).append("\n"); devices.setText(names.length() == 0 ? "Приложение часов не найдено. Проверь установку и Bluetooth." : "Доступны:\n" + names); })
            .addOnFailureListener(e -> devices.setText("Не удалось проверить часы. Проверь сервисы Google Play и Bluetooth.")));
        text("Интернет через ZDT-D", 22);
        text("Помощник использует интернет телефона. Если нужен DNS-профиль или VPN, добавь в выбранные приложения «Hogg — помощник часов». Назначения Samsung менять не нужно.", 16);
        text("Если после блокировки телефона ответы перестают приходить, проверь ограничения фоновой работы Hogg в настройках батареи OnePlus. Пока связь включена, на телефоне есть уведомление помощника.", 14);
        button("Настройки приложения Android", () -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }
    private boolean save() {
        try {
            String value = secret.getText().toString().trim();
            if (!value.isEmpty()) settings.saveKey(value);
            settings.saveModel(model.getText().toString().trim()); secret.setText("");
            secret.setHint(settings.hasKey() ? "Ключ сохранён" : "Вставь ключ Groq");
            if (result != null) result.setText("Настройки сохранены"); return true;
        } catch (Exception e) { if (result != null) result.setText("Не удалось сохранить настройки. Проверь ключ и имя модели."); return false; }
    }
    private void enable() {
        if (!settings.hasKey()) { result.setText("Сначала добавь бесплатный ключ Groq."); return; }
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
    @Override public void onResume() { super.onResume(); main.post(refresh); }
    @Override public void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    @Override public void onDestroy() { if (testClient != null) testClient.cancel(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density*n); }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.rgb(32,45,38)); t.setPadding(0, dp(10), 0, dp(10)); content.addView(t); return t; }
    private EditText edit(String hint, String value) { EditText e = new EditText(this); e.setTextSize(16); e.setHint(hint); e.setText(value); e.setSingleLine(true); content.addView(e); return e; }
    private void button(String value, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(44,102,87)); bg.setCornerRadius(dp(16)); b.setBackground(bg); b.setPadding(dp(12),dp(10),dp(12),dp(10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,dp(7),0,dp(7)); content.addView(b,p); b.setOnClickListener(v -> action.run());
    }
}
