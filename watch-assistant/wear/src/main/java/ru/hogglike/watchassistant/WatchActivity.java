package ru.hogglike.watchassistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.*;
import com.google.android.gms.wearable.*;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class WatchActivity extends Activity implements MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status, questionView, answerView;
    private Button talk, cancel;
    private CheckBox speak;
    private SharedPreferences prefs;
    private MediaRecorder recorder;
    private File audio;
    private String node = "", localNode = "", request = "", spokenId = "", stage = "";
    private boolean busy, ready, checking, resumed;
    private TextToSpeech tts;
    private long recordStart;
    private int recordingSeconds;
    private final Runnable recordTick = new Runnable() { public void run() {
        if (recorder == null) return;
        recordingSeconds = (int)((System.currentTimeMillis() - recordStart)/1000);
        status.setText("Говори… " + recordingSeconds + " / 25 с");
        if (recordingSeconds >= 25) stopRecording(true); else main.postDelayed(this, 500);
    }};
    private final Runnable poll = new Runnable() { public void run() { showResult(); main.postDelayed(this, 1000); }};
    private final Runnable deadline = () -> { if (busy) { cancelRequest(); status.setText("Ответ не пришёл. Проверь телефон и попробуй снова."); } };
    private final Runnable pingDeadline = () -> { if (checking) { checking = false; status.setText("Телефон не ответил. Открой Hogg на телефоне и включи связь."); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = getSharedPreferences("watch", MODE_PRIVATE);
        if (!prefs.contains("conversation")) prefs.edit().putString("conversation", UUID.randomUUID().toString()).apply();
        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(26),dp(28),dp(26),dp(38)); content.setBackgroundColor(Color.rgb(16,25,21)); scroll.addView(content); setContentView(scroll);
        text("Hogg", 23); text("Помощник · 0.1.0", 12);
        status = text("Вопрос обрабатывает телефон", 14);
        talk = button("Задать вопрос", () -> {
            if (recorder != null) { stopRecording(true); return; }
            if (busy) return;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            else startRecording();
        });
        cancel = button("Отмена", () -> { if (recorder != null) stopRecording(false); else cancelRequest(); }); cancel.setVisibility(View.GONE);
        button("Ввести текст", () -> {
            if (busy || recorder != null) return;
            EditText input = new EditText(this); input.setTextSize(16); input.setHint("Твой вопрос"); input.setMaxLines(4);
            new AlertDialog.Builder(this).setTitle("Вопрос").setView(input).setPositiveButton("Отправить", (d,w) -> send(input.getText().toString().trim(), null)).setNegativeButton("Отмена", null).show();
        });
        questionView = text("", 14); answerView = text("Ответ появится здесь", 17);
        speak = new CheckBox(this); speak.setText("Озвучивать"); speak.setTextColor(Color.WHITE); speak.setTextSize(14); speak.setChecked(prefs.getBoolean("speak", true)); content.addView(speak);
        speak.setOnCheckedChangeListener((b,value) -> { prefs.edit().putBoolean("speak", value).apply(); if (!value && tts != null) tts.stop(); });
        button("Прочитать ответ", this::readAnswer);
        button("Новый разговор", () -> {
            if (busy || recorder != null) return;
            prefs.edit().putString("conversation", UUID.randomUUID().toString()).putString("answer", "").putString("question", "").putString("error", "").remove("request").remove("completed").apply();
            request = ""; questionView.setText(""); answerView.setText("Новый разговор");
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
        text("Запись идёт только после нажатия кнопки. На телефоне должна быть включена связь Hogg.", 12);
        tts = new TextToSpeech(this, code -> main.post(() -> {
            if (code == TextToSpeech.SUCCESS && tts != null) { int r = tts.setLanguage(new Locale("ru", "RU")); ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED; }
        }));
        Wearable.getNodeClient(this).getLocalNode().addOnSuccessListener(n -> localNode = n.getId());
    }
    private void startRecording() {
        try {
            if (tts != null) tts.stop();
            audio = new File(getCacheDir(), "question.m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC); recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); recorder.setAudioSamplingRate(16000); recorder.setAudioEncodingBitRate(32000);
            recorder.setMaxDuration(26000); recorder.setOutputFile(audio.getAbsolutePath()); recorder.prepare(); recorder.start();
            recorder.setOnInfoListener((r,what,extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(true); });
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); recordStart = System.currentTimeMillis();
            talk.setText("Отправить вопрос"); cancel.setVisibility(View.VISIBLE); main.post(recordTick);
        } catch (Exception e) { releaseRecorder(); if (audio != null) audio.delete(); status.setText("Не удалось начать запись. Проверь разрешение микрофона."); }
    }
    private void stopRecording(boolean submit) {
        if (recorder == null) return;
        boolean good = System.currentTimeMillis() - recordStart >= 700;
        try { recorder.stop(); } catch (Exception e) { good = false; }
        releaseRecorder(); talk.setText("Задать вопрос"); cancel.setVisibility(View.GONE);
        if (submit && good && audio != null && audio.length() > 0 && audio.length() <= Protocol.MAX_AUDIO) {
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(audio); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) { if (out.size() + n > Protocol.MAX_AUDIO) throw new IOException("limit"); out.write(buffer,0,n); }
                bytes = out.toByteArray();
            }
            catch (Exception e) { status.setText("Не удалось прочитать запись"); audio.delete(); return; }
            audio.delete(); send("", bytes);
        } else { if (audio != null) audio.delete(); status.setText(submit ? "Запись слишком короткая. Повтори вопрос." : "Запись отменена"); }
    }
    private void releaseRecorder() {
        main.removeCallbacks(recordTick); if (recorder != null) { recorder.release(); recorder = null; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void send(String text, byte[] audioBytes) {
        if (busy) return;
        if (audioBytes == null && (text.isEmpty() || text.length() > Protocol.MAX_TEXT)) { status.setText("Введи вопрос длиной до 3000 символов."); return; }
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
                if (audioBytes != null) r.getDataMap().putAsset("audio",Asset.createFromBytes(audioBytes)); else r.getDataMap().putString("text",text);
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
        if (busy || recorder != null) return;
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
            if (e.getPath().equals(Protocol.PONG) && checking) { checking=false;main.removeCallbacks(pingDeadline);status.setText(value.equals("Готов") ? "Телефон готов. Можно задать вопрос." : value); }
            else if(e.getPath().equals(Protocol.PROGRESS) && busy && value.startsWith(request+"\n")) { stage=value.substring(request.length()+1);status.setText(stage); }
        });
    }
    @Override public void onDataChanged(DataEventBuffer events) {
        for(DataEvent e:events) if(e.getType()==DataEvent.TYPE_CHANGED && e.getDataItem().getUri().getPath().startsWith(Protocol.ANSWER)) WatchListener.save(this,DataMapItem.fromDataItem(e.getDataItem()).getDataMap());
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
    @Override public void onPause() { resumed=false; if(recorder!=null)stopRecording(false); Wearable.getMessageClient(this).removeListener(this); Wearable.getDataClient(this).removeListener(this);main.removeCallbacks(poll);if(tts!=null)tts.stop();super.onPause(); }
    @Override public void onDestroy() { releaseRecorder();main.removeCallbacksAndMessages(null);if(tts!=null)tts.shutdown();super.onDestroy(); }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] r) {super.onRequestPermissionsResult(code,p,r);if(code==1 && r.length>0 && r[0]==PackageManager.PERMISSION_GRANTED)startRecording();else status.setText("Для голосового вопроса нужен микрофон. Можно ввести текст.");}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(228,238,232));t.setGravity(Gravity.CENTER);t.setPadding(0,dp(5),0,dp(5));content.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(Color.rgb(12,32,22));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(125,205,170));bg.setCornerRadius(dp(24));b.setBackground(bg);b.setMinHeight(dp(48));b.setPadding(dp(6),dp(6),dp(6),dp(6));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(4),0,dp(4));content.addView(b,p);b.setOnClickListener(v->action.run());return b;}
}
