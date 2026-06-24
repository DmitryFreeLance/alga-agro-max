package ru.algaagro.maxapp.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String publicBaseUrl;
    private String miniAppUrl;
    private List<Long> startupAdminUserIds = new ArrayList<>();
    private final MaxProperties max = new MaxProperties();
    private final AiProperties ai = new AiProperties();

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getMiniAppUrl() {
        return miniAppUrl;
    }

    public void setMiniAppUrl(String miniAppUrl) {
        this.miniAppUrl = miniAppUrl;
    }

    public List<Long> getStartupAdminUserIds() {
        return startupAdminUserIds;
    }

    public void setStartupAdminUserIds(List<Long> startupAdminUserIds) {
        this.startupAdminUserIds = startupAdminUserIds;
    }

    public MaxProperties getMax() {
        return max;
    }

    public AiProperties getAi() {
        return ai;
    }

    public static class MaxProperties {
        private String apiBaseUrl;
        private String botToken;
        private int pollTimeoutSeconds = 30;
        private int pollLimit = 100;
        private Long postTargetChatId;

        public String getApiBaseUrl() {
            return apiBaseUrl;
        }

        public void setApiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String getBotToken() {
            return botToken;
        }

        public void setBotToken(String botToken) {
            this.botToken = botToken;
        }

        public int getPollTimeoutSeconds() {
            return pollTimeoutSeconds;
        }

        public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
            this.pollTimeoutSeconds = pollTimeoutSeconds;
        }

        public int getPollLimit() {
            return pollLimit;
        }

        public void setPollLimit(int pollLimit) {
            this.pollLimit = pollLimit;
        }

        public Long getPostTargetChatId() {
            return postTargetChatId;
        }

        public void setPostTargetChatId(Long postTargetChatId) {
            this.postTargetChatId = postTargetChatId;
        }
    }

    public static class AiProperties {
        private String kieBaseUrl;
        private String kieApiKey;
        private String kieModel = "gpt-5-2";
        private int kieTimeoutSeconds = 300;
        private int kieBatchSize = 500;
        private String kieGeminiApiKey;
        private String kieGeminiModel = "gemini-3-flash-openai";
        private String kieGeminiEndpoint = "/gemini-3-flash-openai/v1/chat/completions";
        private int geminiTimeoutSeconds = 300;

        public String getKieBaseUrl() {
            return kieBaseUrl;
        }

        public void setKieBaseUrl(String kieBaseUrl) {
            this.kieBaseUrl = kieBaseUrl;
        }

        public String getKieApiKey() {
            return kieApiKey;
        }

        public void setKieApiKey(String kieApiKey) {
            this.kieApiKey = kieApiKey;
        }

        public String getKieModel() {
            return kieModel;
        }

        public void setKieModel(String kieModel) {
            this.kieModel = kieModel;
        }

        public int getKieTimeoutSeconds() {
            return kieTimeoutSeconds;
        }

        public void setKieTimeoutSeconds(int kieTimeoutSeconds) {
            this.kieTimeoutSeconds = kieTimeoutSeconds;
        }

        public int getKieBatchSize() {
            return kieBatchSize;
        }

        public void setKieBatchSize(int kieBatchSize) {
            this.kieBatchSize = kieBatchSize;
        }

        public String getKieGeminiApiKey() {
            return kieGeminiApiKey;
        }

        public void setKieGeminiApiKey(String kieGeminiApiKey) {
            this.kieGeminiApiKey = kieGeminiApiKey;
        }

        public String getKieGeminiModel() {
            return kieGeminiModel;
        }

        public void setKieGeminiModel(String kieGeminiModel) {
            this.kieGeminiModel = kieGeminiModel;
        }

        public String getKieGeminiEndpoint() {
            return kieGeminiEndpoint;
        }

        public void setKieGeminiEndpoint(String kieGeminiEndpoint) {
            this.kieGeminiEndpoint = kieGeminiEndpoint;
        }

        public int getGeminiTimeoutSeconds() {
            return geminiTimeoutSeconds;
        }

        public void setGeminiTimeoutSeconds(int geminiTimeoutSeconds) {
            this.geminiTimeoutSeconds = geminiTimeoutSeconds;
        }
    }
}
