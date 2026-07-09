package ru.algaagro.maxapp.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.algaagro.maxapp.config.AppProperties;

class KeyboardFactoryTest {

    @Test
    void researchKeyboardUsesMessageButtonsForReliableContinueFlow() {
        KeyboardFactory keyboardFactory = new KeyboardFactory(new AppProperties());

        List<Map<String, Object>> keyboard = keyboardFactory.researchKeyboard(true);
        Map<String, Object> attachment = keyboard.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) attachment.get("payload");
        @SuppressWarnings("unchecked")
        List<List<Map<String, Object>>> buttons = (List<List<Map<String, Object>>>) payload.get("buttons");

        assertThat(buttons).hasSize(1);
        assertThat(buttons.get(0)).extracting(button -> button.get("type")).containsExactly("message", "message");
        assertThat(buttons.get(0)).extracting(button -> button.get("text")).containsExactly("▶️ Продолжить", "⏹ Остановить");
    }
}
