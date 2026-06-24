package ru.algaagro.maxapp.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.BroadcastLog;

public interface BroadcastLogRepository extends JpaRepository<BroadcastLog, Long> {
    List<BroadcastLog> findTop20ByOrderByCreatedAtDesc();
}
