package ru.hogglike.watchassistant;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.speech.RecognizerIntent;
import android.view.*;
import android.widget.*;
import com.google.android.gms.wearable.*;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WatchActivity extends Activity implements MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status, questionView, answerView;
    private Button talk, cancel;
    private CheckBox speak;
    private SharedPreferences prefs;
    private String node = "", localNode = "", request = "", spokenId = "", stage = "";
    private boolean busy, ready, checking, resumed;
    private TextToSpeech tts;
    private final Runnable poll = new Runnable() { public void run() { showResult(); main.postDelayed(this, 1000); }};
    private final Runnable deadline = () -> { if (busy) { cancelRequest(); status.setText("Ответ не пришёл. Проверь телефон и попробуй снова."); } };
    private final Runnable pingDeadline = () -> { if (checking) { checking = false; status.setText("Телефон не ответил. Открой Hogg на телефоне и включи связь."); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = getSharedPreferences("watch", MODE_PRIVATE);
        if (!prefs.contains("conversation")) prefs.edit().putString("conversation", UUID.randomUUID().toString()).apply();
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(26),dp(28),dp(26),dp(38)); content.setBackgroundColor(Color.rgb(16,25,21)); scroll.addView(content); setContentView(scroll);
        text("Hogg", 23); text("Мост Gemini · 0.2.1", 12);
        status = text("Отвечает Gemini на телефоне", 14);
        talk = button("Продиктовать", this::recognize);
        cancel = button("Отмена", this::cancelRequest); cancel.setVisibility(View.GONE);
        button("Ввести текст", () -> { if (!busy) compose(""); });
        questionView = text("", 14); answerView = text("Ответ появится здесь", 17);
        speak = new CheckBox(this); speak.setText("Озвучивать"); speak.setTextColor(Color.WHITE); speak.setTextSize(14); speak.setChecked(prefs.getBoolean("speak", true)); content.addView(speak);
        speak.setOnCheckedChangeListener((b,value) -> { prefs.edit().putBoolean("speak", value).apply(); if (!value && tts != null) tts.stop(); });
        button("Прочитать ответ", this::readAnswer);
        button("Очистить экран", () -> {
            if (busy) return;
            prefs.edit().putString("conversation", UUID.randomUUID().toString()).putString("answer", "").putString("question", "").putString("error", "").remove("request").remove("completed").apply();
            request = ""; questionView.setText(""); answerView.setText("Экран очищен. Чат Gemini на телефоне сохраняется.");
        });
        button("История", () -> {
            try { JSONArray h = new JSONArray(prefs.getString("history", "[]")); StringBuilder text = new StringBuilder();
                for (int i=h.length()-1; i>=0; i--) text.append(h.getJSONObject(i).optString("question")).append("\n\n").append(h.getJSONObject(i).optString("answer")).append("\n\n────\n\n");
                TextView t = new TextView(this); t.setText(text.length()==0 ? "История пуста" : text); t.setPadding(dp(12),dp(12),dp(12),dp(12)); t.setTextSize(15);
                ScrollView s = new ScrollView(this); s.addView(t); new AlertDialog.Builder(this).setTitle("Последние ответы").setView(s).setPositiveButton("Закрыть",null)
                    .setNeutralButton("Очистить",(d,w)-> prefs.edit().putString("history","[]").apply()).show();
            } catch (Exception e) { status.setText("Не удалось открыть историю"); }
        });
        button("Проверить телефон", this::checkPhone);
        text("Голос распознаётся системным вводом часов. Если он недоступен, введи текст. На телефоне включи связь Hogg и доступ к Gemini. Чат управляется в Gemini на телефоне.", 12);
        tts = new TextToSpeech(this, code -> main.post(() -> {
            if (code == TextToSpeech.SUCCESS && tts != null) { int r = tts.setLanguage(new Locale("ru", "RU")); ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED; }
        }));
        Wearable.getNodeClient(this).getLocalNode().addOnSuccessListener(n -> localNode = n.getId());
    }
    private void recognize() {
        if (busy) return;
        if (tts != null) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU").putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Команда Gemini");
        try { startActivityForResult(i, 44); }
        catch (Exception e) { status.setText("Системный голосовой ввод недоступен. Нажми «Ввести текст»."); }
    }
    @Override public void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 44) return;
        ArrayList<String> values = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && values != null && !values.isEmpty()) compose(values.get(0));
        else status.setText("Вопрос не распознан. Повтори или введи текст.");
    }
    private void compose(String value) {
        if (busy) return;
        EditText input = new EditText(this); input.setTextSize(16); input.setHint("Твой вопрос"); input.setMaxLines(4);
        input.setText(value); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Protocol.MAX_TEXT)});
        new AlertDialog.Builder(this).setTitle("Команда Gemini").setView(input)
            .setPositiveButton("Отправить", (d,w) -> send(input.getText().toString().trim()))
            .setNegativeButton("Отмена", null).show();
    }
    private void send(String text) {
        if (busy) return;
        if (text.isEmpty() || text.length() > Protocol.MAX_TEXT) { status.setText("Введи вопрос длиной до 3000 символов."); return; }
        busy = true; request = UUID.randomUUID().toString(); stage = "Ищу телефон…";
        prefs.edit().putString("request",request).putLong("request_time",System.currentTimeMillis()).putBoolean("busy",true).putString("error","").apply();
        talk.setEnabled(false); cancel.setVisibility(View.VISIBLE); status.setText(stage);
        main.postDelayed(deadline, 120_000);
        final String id = request;
        Wearable.getCapabilityClient(this).getCapability("hogg_ai_phone", CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener(cap -> {
                if (!busy || !request.equals(id)) return;
                Node selected = choose(cap.getNodes()); if (selected == null) { fail("Телефон не найден. Проверь Bluetooth и установку Hogg на телефоне."); return; }
                node = selected.getId();
                prefs.edit().putString("phone_node", node).apply();
                PutDataMapRequest r = PutDataMapRequest.create(Protocol.REQUEST + id);
                r.getDataMap().putString("id",id); r.getDataMap().putString("conversation",prefs.getString("conversation",""));
                r.getDataMap().putLong("created",System.currentTimeMillis());
                r.getDataMap().putString("text",text);
                Wearable.getDataClient(this).putDataItem(r.asPutDataRequest().setUrgent())
                    .addOnSuccessListener(item -> { if (busy && request.equals(id)) { stage = "Передаю вопрос телефону…"; status.setText(stage); } })
                    .addOnFailureListener(e -> { if (busy && request.equals(id)) fail("Не удалось передать вопрос. Проверь Bluetooth."); });
            }).addOnFailureListener(e -> { if (busy && request.equals(id)) fail("Не удалось связаться с телефоном."); });
    }
    private Node choose(Set<Node> nodes) { Node selected = null; for (Node n : nodes) { if (selected == null) selected = n; if (n.isNearby()) return n; } return selected; }
    private void fail(String message) { cancelRequest(); status.setText(message); }
    private void cancelRequest() {
        main.removeCallbacks(deadline);
        if (!node.isEmpty() && Protocol.validId(request)) Wearable.getMessageClient(this).sendMessage(node,Protocol.CANCEL,request.getBytes(StandardCharsets.UTF_8));
        if (!localNode.isEmpty() && Protocol.validId(request)) Wearable.getDataClient(this).deleteDataItems(android.net.Uri.parse("wear://"+localNode+Protocol.REQUEST+request));
        prefs.edit().putBoolean("busy",false).remove("request").apply(); busy=false; request="";
        talk.setEnabled(true); cancel.setVisibility(View.GONE); status.setText("Запрос отменён");
    }
    private void checkPhone() {
        if (busy) return;
        checking = true; status.setText("Проверяю телефон…"); main.removeCallbacks(pingDeadline); main.postDelayed(pingDeadline,10000);
        Wearable.getCapabilityClient(this).getCapability("hogg_ai_phone",CapabilityClient.FILTER_REACHABLE).addOnSuccessListener(cap -> {
            Node n=choose(cap.getNodes()); if(n==null) { checking=false; status.setText("Телефон не найден. Проверь Bluetooth и установку Hogg."); return; }
            node=n.getId(); Wearable.getMessageClient(this).sendMessage(node,Protocol.PING,new byte[0]).addOnFailureListener(e->{checking=false;status.setText("Сообщение телефону не доставлено");});
        }).addOnFailureListener(e->{checking=false;status.setText("Не удалось проверить связь с телефоном");});
    }
    @Override public void onMessageReceived(MessageEvent e) {
        String value = new String(e.getData(),StandardCharsets.UTF_8);
        main.post(() -> {
            if (!e.getSourceNodeId().equals(node)) return;
            if (e.getPath().equals(Protocol.PONG) && checking) { checking=false;main.removeCallbacks(pingDeadline);status.setText(value.equals("Готов") ? "Связь и доступ к Gemini включены. Отправь тестовый вопрос." : value); }
            else if(e.getPath().equals(Protocol.PROGRESS) && busy && value.startsWith(request+"\n")) { stage=value.substring(request.length()+1);status.setText(stage); }
        });
    }
    @Override public void onDataChanged(DataEventBuffer events) {
        for(DataEvent e:events) if(e.getType()==DataEvent.TYPE_CHANGED && e.getDataItem().getUri().getPath().startsWith(Protocol.ANSWER)) WatchListener.save(this,e.getDataItem().getUri(),DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
        main.post(this::showResult);
    }
    private void showResult() {
        String id=prefs.getString("request","");
        if (id.isEmpty() || !id.equals(prefs.getString("completed",""))) return;
        busy=false; main.removeCallbacks(deadline); talk.setEnabled(true); cancel.setVisibility(View.GONE);
        String error=prefs.getString("error",""); String answer=prefs.getString("answer","");
        questionView.setText(prefs.getString("question","")); answerView.setText(error.isEmpty() ? answer : error);
        if (!id.equals(spokenId)) { spokenId=id; status.setText(error.isEmpty() ? "Готово" : "Не удалось получить ответ"); if(error.isEmpty() && speak.isChecked() && resumed && ready) readAnswer(); }
    }
    private void readAnswer() {
        String answer=prefs.getString("answer",""); if(answer.isEmpty()) return;
        if(!ready) { status.setText("Русская озвучка недоступна. Ответ можно прочитать на экране."); return; }
        tts.speak(Protocol.bounded(answer,3500),TextToSpeech.QUEUE_FLUSH,null,"answer");
    }
    @Override public void onResume() {
        super.onResume(); resumed=true;
        Wearable.getMessageClient(this).addListener(this); Wearable.getDataClient(this).addListener(this);
        request=prefs.getString("request",""); busy=prefs.getBoolean("busy",false);
        node=prefs.getString("phone_node",node);
        if(busy) { long remaining=120_000-(System.currentTimeMillis()-prefs.getLong("request_time",0)); if(remaining<=0) fail("Запрос устарел. Отправь вопрос заново."); else {talk.setEnabled(false);cancel.setVisibility(View.VISIBLE);status.setText("Ожидаю ответ телефона…");main.postDelayed(deadline,remaining);} }
        main.post(poll);
    }
    @Override public void onPause() { resumed=false; Wearable.getMessageClient(this).removeListener(this); Wearable.getDataClient(this).removeListener(this);main.removeCallbacks(poll);if(tts!=null)tts.stop();super.onPause(); }
    @Override public void onDestroy() { main.removeCallbacksAndMessages(null);if(tts!=null)tts.shutdown();super.onDestroy(); }
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(228,238,232));t.setGravity(Gravity.CENTER);t.setPadding(0,dp(5),0,dp(5));content.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(Color.rgb(12,32,22));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(125,205,170));bg.setCornerRadius(dp(24));b.setBackground(bg);b.setMinHeight(dp(48));b.setPadding(dp(6),dp(6),dp(6),dp(6));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));content.addView(b,p);b.setOnClickListener(v->action.run());return b;}
}
