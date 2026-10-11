package ru.hogglike.watchassistant;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProtocolTest {
    @Test public void reconnectDoesNotReplayExpiredOrFutureQuestion() {
        assertFalse(Protocol.fresh(1000, 182000));
        assertFalse(Protocol.fresh(50000, 1000));
        assertTrue(Protocol.fresh(1000, 1000));
    }
    @Test public void duplicateAndConcurrentQuestionCannotEnter() {
        Protocol.Gate gate = new Protocol.Gate();
        assertEquals("ok", gate.accept("one", 1000, 1000));
        assertEquals("duplicate", gate.accept("one", 1000, 1000));
        assertEquals("busy", gate.accept("two", 1000, 1000));
        gate.finish("two");
        assertTrue(gate.active("one"));
        gate.finish("one");
        assertEquals("duplicate", gate.accept("one", 1000, 1000));
        assertEquals("ok", gate.accept("two", 1000, 1000));
    }
    @Test public void pathsAndPayloadsAreBounded() {
        assertFalse(Protocol.validId("../../token"));
        assertFalse(Protocol.validId(""));
        assertEquals("прив", Protocol.bounded("привет", 4));
        assertTrue(Protocol.MAX_AUDIO <= 512 * 1024);
    }
    @Test public void errorsExplainLimitRatherThanAdvertisingPaidUpgrade() {
        assertTrue(Protocol.httpError(429).contains("Подожди"));
        assertTrue(Protocol.httpError(401).contains("ключ"));
        assertTrue(Protocol.httpError(403).contains("региона"));
    }
}
