package ru.algaagro.maxapp.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.algaagro.maxapp.config.AppProperties;

@Service
public class StartupService {

    private static final Logger log = LoggerFactory.getLogger(StartupService.class);

    private final AppProperties appProperties;
    private final UserService userService;

    public StartupService(AppProperties appProperties, UserService userService) {
        this.appProperties = appProperties;
        this.userService = userService;
    }

    @PostConstruct
    public void initAdmins() {
        appProperties.getStartupAdminUserIds().stream()
                .filter(id -> id != null && id > 0)
                .forEach(userService::ensureAdminPlaceholder);
        if (!appProperties.getStartupAdminUserIds().isEmpty()) {
            log.info("Loaded {} startup admins", appProperties.getStartupAdminUserIds().size());
        }
    }
}
