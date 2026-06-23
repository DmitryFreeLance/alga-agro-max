package ru.algaagro.maxapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

@Service
public class MaxPollingService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MaxPollingService.class);

    private final ExecutorService botExecutorService;
    private final MaxApiClient maxApiClient;
    private final BotUpdateHandler botUpdateHandler;

    private volatile boolean running;
    private volatile String marker;

    public MaxPollingService(ExecutorService botExecutorService, MaxApiClient maxApiClient, BotUpdateHandler botUpdateHandler) {
        this.botExecutorService = botExecutorService;
        this.maxApiClient = maxApiClient;
        this.botUpdateHandler = botUpdateHandler;
    }

    @Override
    public void start() {
        if (running || !maxApiClient.enabled()) {
            if (!maxApiClient.enabled()) {
                log.warn("MAX polling disabled: bot token is not configured");
            }
            return;
        }
        running = true;
        botExecutorService.submit(this::loop);
    }

    private void loop() {
        log.info("MAX long polling started");
        while (running) {
            try {
                JsonNode response = maxApiClient.getUpdates(marker);
                if (response.hasNonNull("marker")) {
                    marker = response.path("marker").asText();
                }
                JsonNode updates = response.path("updates");
                if (updates.isArray()) {
                    for (JsonNode update : updates) {
                        botUpdateHandler.handle(update);
                    }
                }
            } catch (Exception e) {
                log.warn("Long polling cycle failed: {}", e.getMessage());
                sleepSilently(3000);
            }
        }
        log.info("MAX long polling stopped");
    }

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
