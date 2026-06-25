package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.BotSession;
import ru.algaagro.maxapp.model.SessionState;
import ru.algaagro.maxapp.repository.BotSessionRepository;
import ru.algaagro.maxapp.util.JsonHelper;

@Service
public class BotSessionService {

    private final BotSessionRepository botSessionRepository;
    private final JsonHelper jsonHelper;

    public BotSessionService(BotSessionRepository botSessionRepository, JsonHelper jsonHelper) {
        this.botSessionRepository = botSessionRepository;
        this.jsonHelper = jsonHelper;
    }

    @Transactional
    public BotSession getOrCreate(Long maxUserId) {
        return botSessionRepository.findByMaxUserId(maxUserId).orElseGet(() -> {
            BotSession session = new BotSession();
            session.setMaxUserId(maxUserId);
            session.setState(SessionState.IDLE);
            return botSessionRepository.save(session);
        });
    }

    public Map<String, Object> getPayload(BotSession session) {
        return jsonHelper.readValue(session.getPayloadJson(), new TypeReference<>() { }, new HashMap<>());
    }

    public List<BotSession> findByState(SessionState state) {
        return botSessionRepository.findAllByStateOrderByUpdatedAtDesc(state);
    }

    @Transactional
    public void update(BotSession session, SessionState state, Map<String, Object> payload) {
        session.setState(state);
        session.setPayloadJson(jsonHelper.writeValue(payload));
        botSessionRepository.save(session);
    }

    @Transactional
    public void reset(Long maxUserId) {
        BotSession session = getOrCreate(maxUserId);
        session.setState(SessionState.IDLE);
        session.setPayloadJson("{}");
        botSessionRepository.save(session);
    }
}
