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
    private final PostButtonService postButtonService;
    private final ProductService productService;

    public StartupService(AppProperties appProperties, UserService userService, PostButtonService postButtonService, ProductService productService) {
        this.appProperties = appProperties;
        this.userService = userService;
        this.postButtonService = postButtonService;
        this.productService = productService;
    }

    @PostConstruct
    public void init() {
        appProperties.getStartupAdminUserIds().stream()
                .filter(id -> id != null && id > 0)
                .forEach(userService::ensureAdminPlaceholder);
        if (!appProperties.getStartupAdminUserIds().isEmpty()) {
            log.info("Loaded {} startup admins", appProperties.getStartupAdminUserIds().size());
        }
        postButtonService.ensureDefaultButtons();
        log.info("Default post buttons ensured");
        int normalizedSeedPackages = productService.normalizeExistingSeedPackageDescriptions();
        if (normalizedSeedPackages > 0) {
            log.info("Normalized seed package descriptions to Big Bag for {} products", normalizedSeedPackages);
        }
    }
}
