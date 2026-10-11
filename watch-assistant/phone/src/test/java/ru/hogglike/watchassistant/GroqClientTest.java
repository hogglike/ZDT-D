package ru.hogglike.watchassistant;
import org.junit.*;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;
import org.json.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
public class GroqClientTest {
    private MockWebServer server;
    private GroqClient client;
    @Before public void start() throws Exception {
        server=new MockWebServer();server.start();
        client=new GroqClient("test_key",Protocol.DEFAULT_MODEL,p->(HttpURLConnection)server.url("/"+p).url().openConnection());
    }
    @After public void stop() throws Exception {server.shutdown();}
    @Test public void chatSendsRussianInstructionAndConversationOverSocket() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"Привет!\"}}]}"));
        JSONArray h=new JSONArray().put(new JSONObject().put("role","user").put("content","Меня зовут Hogg"));
        assertEquals("Привет!",client.answer("Как меня зовут?",h));
        RecordedRequest r=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(r);
        assertEquals("/chat/completions",r.getPath());assertEquals("Bearer test_key",r.getHeader("Authorization"));
        JSONObject body=new JSONObject(r.getBody().readUtf8());
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("русском"));
        assertEquals("Как меня зовут?",body.getJSONArray("messages").getJSONObject(2).getString("content"));
    }
    @Test public void voiceMultipartPreservesAudioAndSelectsRussian() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"text\":\"Привет, часы\"}"));
        byte[] audio="m4a-fixture-data".getBytes(StandardCharsets.UTF_8);
        assertEquals("Привет, часы",client.transcribe(audio));
        RecordedRequest r=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("/audio/transcriptions",r.getPath());
        String body=r.getBody().readUtf8();assertTrue(body.contains("name=\"language\"\r\n\r\nru\r\n"));
        assertTrue(body.contains("whisper-large-v3-turbo"));assertTrue(body.contains("m4a-fixture-data"));
        assertTrue(body.contains("filename=\"question.m4a\""));assertFalse(body.contains("test_key"));
    }
    @Test public void rejectsRedirectRatherThanLeakingKeyOrBypassingProxy() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","http://localhost:9/leak"));
        try{client.answer("Привет",new JSONArray());fail();}catch(Exception e){assertTrue(e.getMessage().contains("302"));}
        assertEquals(1,server.getRequestCount());
    }
    @Test public void quotaErrorDoesNotExposeResponseOrRetry() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("private-key-secret"));
        try{client.answer("Привет",new JSONArray());fail();}catch(Exception e){String message=GroqClient.readable(e);assertTrue(message.contains("лимит"));assertFalse(message.contains("private"));}
        assertEquals(1,server.getRequestCount());
    }
    @Test public void cancelBeforeSendProducesNoNetworkRequest() throws Exception {
        client.cancel();try{client.answer("Привет",new JSONArray());fail();}catch(Exception e){assertEquals("Запрос отменён.",e.getMessage());}
        assertEquals(0,server.getRequestCount());
    }
}
