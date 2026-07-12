package ru.algaagro.maxapp.repository;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.algaagro.maxapp.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByMaxUserId(Long maxUserId);
    Page<AppUser> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<AppUser> findAllByOrderByLastSeenAtDesc();
    List<AppUser> findAllByReferredByMaxUserIdOrderByCreatedAtDesc(Long referredByMaxUserId);
    long countByReferredByMaxUserId(Long referredByMaxUserId);
    long countByCreatedAtGreaterThanEqual(Instant createdAt);
}
