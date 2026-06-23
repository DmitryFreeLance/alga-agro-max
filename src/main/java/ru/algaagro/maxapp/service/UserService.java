package ru.algaagro.maxapp.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.AppUser;
import ru.algaagro.maxapp.repository.AppUserRepository;

@Service
public class UserService {

    private final AppUserRepository appUserRepository;

    public UserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public AppUser touchUser(Long maxUserId, String displayName, String username) {
        AppUser user = appUserRepository.findByMaxUserId(maxUserId).orElseGet(AppUser::new);
        user.setMaxUserId(maxUserId);
        user.setDisplayName(displayName == null || displayName.isBlank() ? "Пользователь MAX" : displayName);
        user.setUsername(username);
        user.setLastSeenAt(Instant.now());
        return appUserRepository.save(user);
    }

    @Transactional
    public void ensureAdminPlaceholder(Long maxUserId) {
        AppUser user = appUserRepository.findByMaxUserId(maxUserId).orElseGet(AppUser::new);
        user.setMaxUserId(maxUserId);
        if (user.getDisplayName() == null) {
            user.setDisplayName("Администратор " + maxUserId);
        }
        user.setAdmin(true);
        user.setLastSeenAt(Instant.now());
        appUserRepository.save(user);
    }

    public boolean isAdmin(Long maxUserId) {
        return appUserRepository.findByMaxUserId(maxUserId).map(AppUser::isAdmin).orElse(false);
    }

    public Page<AppUser> listUsers(int page, int size) {
        return appUserRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), Math.max(1, size)));
    }

    public Optional<AppUser> findByMaxUserId(Long maxUserId) {
        return appUserRepository.findByMaxUserId(maxUserId);
    }

    @Transactional
    public boolean grantAdmin(Long targetUserId) {
        Optional<AppUser> target = appUserRepository.findByMaxUserId(targetUserId);
        if (target.isEmpty()) {
            return false;
        }
        AppUser user = target.get();
        user.setAdmin(true);
        appUserRepository.save(user);
        return true;
    }

    public long countUsers() {
        return appUserRepository.count();
    }

    public List<Long> findAdminUserIds() {
        return appUserRepository.findAll().stream()
                .filter(AppUser::isAdmin)
                .map(AppUser::getMaxUserId)
                .toList();
    }
}
