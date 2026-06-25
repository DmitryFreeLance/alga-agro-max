package ru.algaagro.maxapp.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String publicBaseUrl;
    private String miniAppUrl;
    private String managerContactUrl = "https://max.ru/id27849376";
    private String managerDeepLink = "max://user/27849376";
    private String storageDir = "/data/storage";
    private List<Long> startupAdminUserIds = new ArrayList<>();
    private final MaxProperties max = new MaxProperties();
    private final AiProperties ai = new AiProperties();
    private final BitrixProperties bitrix = new BitrixProperties();

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

    public String getManagerContactUrl() {
        return managerContactUrl;
    }

    public void setManagerContactUrl(String managerContactUrl) {
        this.managerContactUrl = managerContactUrl;
    }

    public String getManagerDeepLink() {
        return managerDeepLink;
    }

    public void setManagerDeepLink(String managerDeepLink) {
        this.managerDeepLink = managerDeepLink;
    }

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
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

    public BitrixProperties getBitrix() {
        return bitrix;
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
        private String kieGeminiModel = "gemini-3-flash";
        private String kieGeminiEndpoint = "/gemini-3-flash/v1/chat/completions";
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

    public static class BitrixProperties {
        private String webhookBaseUrl;
        private boolean syncEnabled = true;
        private Integer catalogId;
        private Integer basePriceTypeId;
        private String currencyId = "RUB";
        private long pollIntervalMs = 180_000L;
        private long initialSyncDelayMs = 45_000L;
        private String leadTitlePrefix = "Заказ из Alga Agro MAX";
        private Integer leadAssignedById;
        private String leadSourceId;
        private String originatorId = "ALGA_AGRO_MAX";

        public String getWebhookBaseUrl() {
            return webhookBaseUrl;
        }

        public void setWebhookBaseUrl(String webhookBaseUrl) {
            this.webhookBaseUrl = webhookBaseUrl;
        }

        public boolean isSyncEnabled() {
            return syncEnabled;
        }

        public void setSyncEnabled(boolean syncEnabled) {
            this.syncEnabled = syncEnabled;
        }

        public Integer getCatalogId() {
            return catalogId;
        }

        public void setCatalogId(Integer catalogId) {
            this.catalogId = catalogId;
        }

        public Integer getBasePriceTypeId() {
            return basePriceTypeId;
        }

        public void setBasePriceTypeId(Integer basePriceTypeId) {
            this.basePriceTypeId = basePriceTypeId;
        }

        public String getCurrencyId() {
            return currencyId;
        }

        public void setCurrencyId(String currencyId) {
            this.currencyId = currencyId;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getInitialSyncDelayMs() {
            return initialSyncDelayMs;
        }

        public void setInitialSyncDelayMs(long initialSyncDelayMs) {
            this.initialSyncDelayMs = initialSyncDelayMs;
        }

        public String getLeadTitlePrefix() {
            return leadTitlePrefix;
        }

        public void setLeadTitlePrefix(String leadTitlePrefix) {
            this.leadTitlePrefix = leadTitlePrefix;
        }

        public Integer getLeadAssignedById() {
            return leadAssignedById;
        }

        public void setLeadAssignedById(Integer leadAssignedById) {
            this.leadAssignedById = leadAssignedById;
        }

        public String getLeadSourceId() {
            return leadSourceId;
        }

        public void setLeadSourceId(String leadSourceId) {
            this.leadSourceId = leadSourceId;
        }

        public String getOriginatorId() {
            return originatorId;
        }

        public void setOriginatorId(String originatorId) {
            this.originatorId = originatorId;
        }
    }
}
