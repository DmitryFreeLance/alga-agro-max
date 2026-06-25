package ru.algaagro.maxapp.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.AppUser;
import ru.algaagro.maxapp.repository.AppUserRepository;
import ru.algaagro.maxapp.util.JsonHelper;

@Service
public class UserService {

    private final AppUserRepository appUserRepository;
    private final JsonHelper jsonHelper;

    public UserService(AppUserRepository appUserRepository, JsonHelper jsonHelper) {
        this.appUserRepository = appUserRepository;
        this.jsonHelper = jsonHelper;
    }

    @Transactional
    public AppUser touchUser(Long maxUserId, String displayName, String username) {
        AppUser user = appUserRepository.findByMaxUserId(maxUserId).orElseGet(AppUser::new);
        user.setMaxUserId(maxUserId);
        user.setDisplayName(displayName == null || displayName.isBlank() ? "Пользователь MAX" : displayName);
        user.setUsername(username);
        user.setLastSeenAt(Instant.now());
        ensureStateDefaults(user);
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
        ensureStateDefaults(user);
        appUserRepository.save(user);
    }

    @Transactional
    public AppUser syncClientState(ClientStateCommand command) {
        AppUser user = appUserRepository.findByMaxUserId(command.maxUserId()).orElseGet(AppUser::new);
        user.setMaxUserId(command.maxUserId());
        if (command.displayName() != null && !command.displayName().isBlank()) {
            user.setDisplayName(command.displayName().trim());
        } else if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
            user.setDisplayName("Пользователь MAX");
        }
        user.setUsername(command.username() == null || command.username().isBlank() ? user.getUsername() : command.username().trim());
        Instant now = Instant.now();
        user.setLastSeenAt(now);
        user.setCartJson(jsonHelper.writeValue(command.cartItems() == null ? List.of() : command.cartItems()));
        user.setCheckoutDraftJson(jsonHelper.writeValue(command.checkoutDraft() == null ? Map.of() : command.checkoutDraft()));
        user.setCartUpdatedAt(now);
        user.setCheckoutDraftUpdatedAt(now);
        ensureStateDefaults(user);
        return appUserRepository.save(user);
    }

    @Transactional
    public void clearClientState(Long maxUserId) {
        if (maxUserId == null) {
            return;
        }
        appUserRepository.findByMaxUserId(maxUserId).ifPresent(user -> {
            Instant now = Instant.now();
            user.setCartJson("[]");
            user.setCheckoutDraftJson("{}");
            user.setCartUpdatedAt(now);
            user.setCheckoutDraftUpdatedAt(now);
            user.setLastSeenAt(now);
            ensureStateDefaults(user);
            appUserRepository.save(user);
        });
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

    public long countUsersCreatedThisMonth() {
        Instant monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return appUserRepository.countByCreatedAtGreaterThanEqual(monthStart);
    }

    public List<Long> findAdminUserIds() {
        return appUserRepository.findAll().stream()
                .filter(AppUser::isAdmin)
                .map(AppUser::getMaxUserId)
                .toList();
    }

    public List<Long> findAllUserIds() {
        return appUserRepository.findAll().stream()
                .map(AppUser::getMaxUserId)
                .toList();
    }

    public List<AppUser> listCustomersByLastSeen() {
        return appUserRepository.findAllByOrderByLastSeenAtDesc();
    }

    private void ensureStateDefaults(AppUser user) {
        if (user.getCartJson() == null || user.getCartJson().isBlank()) {
            user.setCartJson("[]");
        }
        if (user.getCheckoutDraftJson() == null || user.getCheckoutDraftJson().isBlank()) {
            user.setCheckoutDraftJson("{}");
        }
    }

    public record ClientStateCommand(
            Long maxUserId,
            String displayName,
            String username,
            List<Map<String, Object>> cartItems,
            Map<String, Object> checkoutDraft
    ) {
    }
}
