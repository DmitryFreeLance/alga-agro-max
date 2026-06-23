package ru.algaagro.maxapp.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.BotSession;

public interface BotSessionRepository extends JpaRepository<BotSession, Long> {
    Optional<BotSession> findByMaxUserId(Long maxUserId);
}
