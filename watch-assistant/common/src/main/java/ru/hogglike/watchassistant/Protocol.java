package ru.hogglike.watchassistant;

import java.util.LinkedHashMap;
import java.util.Map;

/** Device-independent limits. Requests expire rather than being replayed after reconnect. */
public final class Protocol {
    public static final String REQUEST = "/hogg-ai/request/";
    public static final String ANSWER = "/hogg-ai/answer/";
    public static final String PROGRESS = "/hogg-ai/progress";
    public static final String PING = "/hogg-ai/ping";
    public static final String PONG = "/hogg-ai/pong";
    public static final String CANCEL = "/hogg-ai/cancel";
    public static final int MAX_AUDIO = 512 * 1024;
    public static final int MAX_TEXT = 3000;
    public static final int MAX_ANSWER = 6000;
    public static final long MAX_AGE_MS = 180_000;
    private Protocol() {}
    public static boolean validId(String id) { return id != null && id.matches("[a-zA-Z0-9_-]{1,64}"); }
    public static boolean fresh(long created, long now) {
        return created > 0 && created <= now + 30_000 && now - created <= MAX_AGE_MS;
    }
    public static String bounded(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
    /** Prevent duplicate delivery from processing a question a second time. */
    public static final class Gate {
        private final Map<String, Boolean> seen = new LinkedHashMap<>();
        private String active = "";
        public synchronized String accept(String id, long created, long now) {
            if (!validId(id) || !fresh(created, now)) return "expired";
            if (seen.containsKey(id)) return "duplicate";
            if (!active.isEmpty()) return "busy";
            seen.put(id, true);
            while (seen.size() > 128) seen.remove(seen.keySet().iterator().next());
            active = id;
            return "ok";
        }
        public synchronized boolean active(String id) { return active.equals(id); }
        public synchronized void finish(String id) { if (active.equals(id)) active = ""; }
    }
}
