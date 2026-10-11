package ru.hogglike.watchassistant;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class GeminiTextTest {
    private GeminiText.Line t(String s) { return new GeminiText.Line(s, false, false, false); }
    private GeminiText.Line end() { return new GeminiText.Line("Копировать ответ", false, true, true); }
    @Test public void lastQuestionWinsAndOldConversationIsNotReturned() {
        List<GeminiText.Line> l = Arrays.asList(t("Старый вопрос"), t("Старый ответ"), end(), t("Новый вопрос"), t("Новый ответ"), end());
        assertEquals("Новый ответ", GeminiText.answer(l, "Новый вопрос", "Старый ответ", false));
        assertEquals("", GeminiText.answer(l, "Нет такого вопроса", "", false));
    }
    @Test public void refusesEditorOnlyPartialGenerationAndMissingBoundary() {
        assertEquals("", GeminiText.answer(Arrays.asList(new GeminiText.Line("Вопрос", true, false, false), t("Старый ответ"), end()), "Вопрос", "", false));
        assertEquals("", GeminiText.answer(Arrays.asList(t("Вопрос"), t("Ответ"), end()), "Вопрос", "", true));
        assertEquals("", GeminiText.answer(Arrays.asList(t("Вопрос"), t("Ответ")), "Вопрос", "", false));
        assertEquals("", GeminiText.answer(Arrays.asList(t("Вопрос"), t("Ответ"), end()), "Вопрос", "Ответ", false));
    }
    @Test public void actionsAreExplicitAndDoNotClickFeedbackOrConfirmation() {
        assertTrue(GeminiText.send("Отправить запрос")); assertTrue(GeminiText.keyboard("Клавиатура"));
        assertFalse(GeminiText.send("Отправить отзыв")); assertFalse(GeminiText.send("Подтвердить отправку"));
        assertFalse(GeminiText.send("Отправить сообщение"));
        assertTrue(GeminiText.prompt("Спросите Gemini")); assertTrue(GeminiText.prompt("Ask Gemini"));
        assertFalse(GeminiText.prompt("Сообщение")); assertFalse(GeminiText.prompt("Получатель"));
        assertFalse(GeminiText.copy("Копировать ссылку"));
        assertTrue(GeminiText.unlock("Разблокируйте телефон, чтобы продолжить"));
        assertFalse(GeminiText.unlock("Ответ: разблокируйте телефон, если хотите изменить настройки"));
    }
}
