package ru.hogglike.watchassistant;

import java.util.*;

/** Conservative semantic matching. Unknown layouts must fail rather than return another turn. */
public final class GeminiText {
    private GeminiText() {}
    public static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    public static boolean send(String s) { return Arrays.asList("отправить", "отправить запрос", "отправить сообщение", "send", "send message", "send prompt", "submit").contains(norm(s)); }
    public static boolean keyboard(String s) { return Arrays.asList("клавиатура", "открыть клавиатуру", "ввести текст", "ввод с клавиатуры", "keyboard", "open keyboard", "type").contains(norm(s)); }
    public static boolean copy(String s) { return Arrays.asList("копировать ответ", "скопировать ответ", "copy response", "copy answer").contains(norm(s)); }
    public static boolean generating(String s) { return Arrays.asList("остановить ответ", "остановить генерацию", "остановить", "stop response", "stop generating").contains(norm(s)); }
    public static boolean unlock(String s) { String n = norm(s); return n.startsWith("разблокируйте телефон") || n.startsWith("разблокируй телефон") || n.startsWith("unlock your phone") || n.startsWith("please unlock"); }
    public static boolean chrome(String s) { String n = norm(s); return n.isEmpty() || Arrays.asList("gemini", "google", "показать больше", "show more", "прослушать", "listen", "нравится", "не нравится", "good response", "bad response").contains(n) || send(s) || keyboard(s) || copy(s) || generating(s); }
    public static final class Line {
        public final String text;
        public final boolean editable, action, responseEnd;
        public Line(String text, boolean editable, boolean action, boolean responseEnd) { this.text = text == null ? "" : text; this.editable = editable; this.action = action; this.responseEnd = responseEnd; }
    }
    /** Require the exact submitted question followed by answer text and a response-specific end marker. */
    public static String answer(List<Line> lines, String question, String previousAnswer, boolean busy) {
        if (busy || norm(question).isEmpty()) return "";
        int q = -1;
        for (int i = 0; i < lines.size(); i++) if (!lines.get(i).editable && norm(lines.get(i).text).equals(norm(question))) q = i;
        if (q < 0) return "";
        StringBuilder text = new StringBuilder(); boolean ended = false;
        for (int i = q + 1; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.responseEnd) { ended = true; break; }
            if (l.editable || l.action || chrome(l.text)) continue;
            if (text.length() > 0) text.append('\n'); text.append(l.text);
        }
        String result = text.toString().trim();
        // Same answer without a provably new turn is deliberately not accepted.
        if (!ended || norm(result).equals(norm(previousAnswer))) return "";
        return Protocol.bounded(result, Protocol.MAX_ANSWER);
    }
}
