package ru.hogglike.watchassistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Phone only. No key or HTTP endpoint is ever included in Wear data. */
final class GroqClient {
    private final String key, model;
    interface ConnectionFactory { HttpURLConnection open(String path) throws Exception; }
    private final ConnectionFactory factory;
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;
    GroqClient(String key, String model) {
        this(key, model, path -> (HttpURLConnection) new URL("https://api.groq.com/openai/v1/" + path).openConnection());
    }
    GroqClient(String key, String model, ConnectionFactory factory) { this.key = key; this.model = model; this.factory = factory; }
    void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
    private void check() throws IOException { if (cancelled) throw new IOException("Запрос отменён."); }
    private HttpURLConnection open(String path, String contentType) throws Exception {
        check();
        if (key.isEmpty()) throw new IOException("Добавь бесплатный ключ Groq в приложении на телефоне.");
        HttpURLConnection c = factory.open(path);
        connection = c; c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(45000);
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", contentType);
        check(); return c;
    }
    private JSONObject result(HttpURLConnection c) throws Exception {
        check(); int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException(Protocol.httpError(code));
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) { check(); if (out.size() + n > 128 * 1024) throw new IOException("Слишком большой ответ сервиса."); out.write(buf, 0, n); }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
    }
    String transcribe(byte[] audio) throws Exception {
        if (audio.length == 0 || audio.length > Protocol.MAX_AUDIO) throw new IOException("Запись слишком большая или пустая. Запиши вопрос заново.");
        String boundary = "hogg-" + UUID.randomUUID();
        HttpURLConnection c = open("audio/transcriptions", "multipart/form-data; boundary=" + boundary);
        try {
            try (OutputStream out = c.getOutputStream()) {
                field(out, boundary, "model", "whisper-large-v3-turbo");
                field(out, boundary, "language", "ru"); field(out, boundary, "response_format", "json");
                bytes(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"question.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n");
                out.write(audio); bytes(out, "\r\n--" + boundary + "--\r\n");
            }
            String text = result(c).optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("Речь не распознана. Попробуй говорить ближе к часам.");
            return Protocol.bounded(text, Protocol.MAX_TEXT);
        } finally { c.disconnect(); connection = null; }
    }
    String answer(String question, JSONArray history) throws Exception {
        if (question.trim().isEmpty() || question.length() > Protocol.MAX_TEXT) throw new IOException("Вопрос пустой или слишком длинный.");
        JSONArray messages = new JSONArray(); messages.put(new JSONObject().put("role", "system").put("content", Protocol.SYSTEM));
        for (int i = Math.max(0, history.length() - 6); i < history.length(); i++) {
            JSONObject m = history.optJSONObject(i); if (m == null) continue;
            String role = m.optString("role"); if (!role.equals("user") && !role.equals("assistant")) continue;
            messages.put(new JSONObject().put("role", role).put("content", Protocol.bounded(m.optString("content"), 1500)));
        }
        messages.put(new JSONObject().put("role", "user").put("content", question));
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("max_completion_tokens", 1800).put("stream", false);
        HttpURLConnection c = open("chat/completions", "application/json; charset=utf-8");
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            String answer = result(c).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            if (answer.isEmpty() || answer.equals("null")) throw new IOException("ИИ вернул пустой ответ. Попробуй повторить вопрос или выбрать другую модель.");
            return Protocol.bounded(answer, Protocol.MAX_ANSWER);
        } finally { c.disconnect(); connection = null; }
    }
    private static void field(OutputStream out, String b, String name, String value) throws IOException {
        bytes(out, "--" + b + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }
    private static void bytes(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    static String readable(Exception e) {
        if (e instanceof SocketTimeoutException) return "Телефон не дождался ответа ИИ. Проверь интернет и попробуй снова.";
        if (e instanceof UnknownHostException) return "Телефон не смог найти api.groq.com. Проверь DNS или VPN для помощника.";
        if (e instanceof javax.net.ssl.SSLException) return "Не удалось установить защищённое соединение с Groq. Проверь сеть и дату телефона.";
        String m = e.getMessage();
        if (m != null && (m.startsWith("Ключ") || m.startsWith("Groq") || m.startsWith("Достигнут") || m.startsWith("Сервис") || m.startsWith("Ошибка сервиса") || m.startsWith("Добавь") || m.startsWith("Запрос") || m.startsWith("Речь") || m.startsWith("Запись") || m.startsWith("Вопрос") || m.startsWith("ИИ") || m.startsWith("Слишком"))) return m;
        return "Ошибка соединения с ИИ. Проверь интернет телефона и повтори вопрос.";
    }
}
