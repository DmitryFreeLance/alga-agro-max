package ru.algaagro.maxapp.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.repository.CatalogProductRepository;
import ru.algaagro.maxapp.util.CatalogStructure;

@Service
public class ProductResearchLinksService {

    private static final Logger log = LoggerFactory.getLogger(ProductResearchLinksService.class);
    private static final int RESEARCH_LINKS_BATCH_SIZE = 12;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration BITRIX_SYNC_WAIT_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration BITRIX_SYNC_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final String SEARCH_URL_TEMPLATE = "https://www.agroxxi.ru/search.html?text=%s";
    private static final Pattern PREP_LINK_PATTERN = Pattern.compile(
            "<a[^>]+href\\s*=\\s*\"(?<href>/goshandbook/prep/[^\"]+)\"[^>]*>(?<title>.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private final ExecutorService importExecutorService;
    private final CatalogProductRepository catalogProductRepository;
    private final ProductService productService;
    private final ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ConcurrentHashMap<Long, ResearchLinksSession> sessions = new ConcurrentHashMap<>();

    public ProductResearchLinksService(
            ExecutorService importExecutorService,
            CatalogProductRepository catalogProductRepository,
            ProductService productService,
            ObjectProvider<BitrixSyncService> bitrixSyncServiceProvider
    ) {
        this.importExecutorService = importExecutorService;
        this.catalogProductRepository = catalogProductRepository;
        this.productService = productService;
        this.bitrixSyncServiceProvider = bitrixSyncServiceProvider;
    }

    public CompletableFuture<Void> startResearchAsync(
            Long initiatedBy,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        ResearchLinksSession existing = sessions.get(initiatedBy);
        if (existing != null) {
            onFailure.accept("Research links уже выполняется. Используйте «Продолжить до конца» или «Остановить» в последнем сообщении.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                waitForBitrixSyncIfNeeded();
                List<CatalogProduct> activeProducts = catalogProductRepository.findAllByActiveTrue().stream()
                        .filter(this::shouldResearchProduct)
                        .sorted((left, right) -> String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName())))
                        .toList();
                if (activeProducts.isEmpty()) {
                    onCompleted.accept("🔗 В разделе пестицидов не найдено активных товаров для поиска ссылок AgroXXI.");
                    return;
                }
                ResearchLinksSession session = new ResearchLinksSession(initiatedBy, activeProducts);
                sessions.put(initiatedBy, session);
                log.info("Research links session initialized. userId={}, targets={}", initiatedBy, activeProducts.size());
                processNextBatch(session, onBatchReady, onCompleted, onFailure);
            } catch (Exception e) {
                sessions.remove(initiatedBy);
                onFailure.accept(e.getMessage());
            }
        }, importExecutorService);
    }

    public CompletableFuture<Void> continueResearchAsync(
            Long initiatedBy,
            boolean runToCompletion,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        ResearchLinksSession session = sessions.get(initiatedBy);
        if (session == null) {
            onFailure.accept("Нет активного researchlinks. Запустите /researchlinks заново.");
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            if (runToCompletion) {
                processRemainingBatches(session, onCompleted, onFailure);
            } else {
                processNextBatch(session, onBatchReady, onCompleted, onFailure);
            }
        }, importExecutorService);
    }

    public String stopResearch(Long initiatedBy) {
        ResearchLinksSession session = sessions.remove(initiatedBy);
        if (session == null) {
            return "Активный researchlinks не найден.";
        }
        session.stopped = true;
        return "Researchlinks остановлен. Проверено " + session.processed + " из " + session.targets.size() + " товаров.";
    }

    public boolean hasResearchSession(Long initiatedBy) {
        return initiatedBy != null && sessions.containsKey(initiatedBy);
    }

    private void processNextBatch(
            ResearchLinksSession session,
            Consumer<BatchReport> onBatchReady,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        if (!session.processing.compareAndSet(false, true)) {
            onFailure.accept("Эта партия еще обрабатывается. Дождитесь завершения текущего шага.");
            return;
        }
        try {
            if (session.stopped) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept("Researchlinks остановлен.");
                return;
            }
            if (session.processed >= session.targets.size()) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept(buildFinalSummary(session));
                return;
            }
            BatchReport report = processBatch(session);
            boolean hasMore = report.hasMore();
            onBatchReady.accept(report);
            if (!hasMore) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept(buildFinalSummary(session));
            }
        } catch (Exception e) {
            sessions.remove(session.initiatedBy);
            onFailure.accept(e.getMessage());
        } finally {
            session.processing.set(false);
        }
    }

    private void processRemainingBatches(
            ResearchLinksSession session,
            Consumer<String> onCompleted,
            Consumer<String> onFailure
    ) {
        if (!session.processing.compareAndSet(false, true)) {
            onFailure.accept("Эта партия еще обрабатывается. Дождитесь завершения текущего шага.");
            return;
        }
        try {
            if (session.stopped) {
                sessions.remove(session.initiatedBy);
                onCompleted.accept("Researchlinks остановлен.");
                return;
            }
            while (!session.stopped && session.processed < session.targets.size()) {
                processBatch(session);
            }
            sessions.remove(session.initiatedBy);
            onCompleted.accept(session.stopped ? "Researchlinks остановлен." : buildFinalSummary(session));
        } catch (Exception e) {
            sessions.remove(session.initiatedBy);
            onFailure.accept(e.getMessage());
        } finally {
            session.processing.set(false);
        }
    }

    private BatchReport processBatch(ResearchLinksSession session) {
        int start = session.processed;
        int end = Math.min(session.targets.size(), start + RESEARCH_LINKS_BATCH_SIZE);
        List<CatalogProduct> batchProducts = session.targets.subList(start, end);
        List<String> lines = new ArrayList<>();
        log.info("Research links batch started. userId={}, start={}, end={}, size={}", session.initiatedBy, start + 1, end, batchProducts.size());

        for (CatalogProduct product : batchProducts) {
            String productName = firstNonBlank(product.getName(), "ID " + product.getId());
            try {
                MatchResult match = resolveAgroxxiLink(productName);
                if (match.url() == null || match.url().isBlank()) {
                    session.unresolved++;
                    session.unresolvedProducts.add(productName);
                    lines.add("• " + productName + " → ссылка не найдена");
                    continue;
                }
                String previousUrl = blankToNull(product.getAgroxxiUrl());
                if (Objects.equals(previousUrl, match.url())) {
                    session.unchanged++;
                    lines.add("• " + productName + " → ссылка уже актуальна");
                    continue;
                }
                productService.updateProductAgroxxiUrl(product, match.url(), false);
                session.updated++;
                lines.add("• " + productName + " → " + match.url() + " [обновлено]");
            } catch (Exception exception) {
                session.failed++;
                session.failedProducts.add(productName);
                log.warn("Research links failed for product {} (id={}): {}", product.getName(), product.getId(), exception.getMessage());
                lines.add("• " + productName + " → ошибка поиска ссылки");
            }
        }

        session.processed = end;
        boolean hasMore = end < session.targets.size() && !session.stopped;
        return new BatchReport(buildBatchReportText(session, start + 1, end, lines, hasMore), hasMore);
    }

    private MatchResult resolveAgroxxiLink(String productName) throws IOException, InterruptedException {
        String query = URLEncoder.encode(productName == null ? "" : productName.trim(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEARCH_URL_TEMPLATE.formatted(query)))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("AgroXXI вернул код " + response.statusCode());
        }
        return findBestMatch(productName, response.body());
    }

    private MatchResult findBestMatch(String productName, String html) {
        String normalizedProductName = normalizeName(productName);
        Matcher matcher = PREP_LINK_PATTERN.matcher(html == null ? "" : html);
        MatchResult best = MatchResult.empty();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        while (matcher.find()) {
            String href = matcher.group("href");
            String title = cleanHtml(matcher.group("title"));
            String absoluteUrl = href == null || href.isBlank() ? "" : "https://www.agroxxi.ru" + href.trim();
            if (absoluteUrl.isBlank() || seen.putIfAbsent(absoluteUrl, Boolean.TRUE) != null) {
                continue;
            }
            double score = computeMatchScore(normalizedProductName, normalizeName(title));
            if (score > best.score()) {
                best = new MatchResult(absoluteUrl, title, score);
            }
        }
        return best.score() >= 0.72d ? best : MatchResult.empty();
    }

    private double computeMatchScore(String left, String right) {
        if (left.isBlank() || right.isBlank()) {
            return 0d;
        }
        if (left.equals(right)) {
            return 1d;
        }
        if (left.startsWith(right) || right.startsWith(left)) {
            return 0.92d;
        }
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0d;
        }
        if (!Objects.equals(leftTokens.get(0), rightTokens.get(0))) {
            return 0d;
        }
        long overlap = leftTokens.stream().filter(rightTokens::contains).count();
        double coverage = overlap / (double) Math.max(leftTokens.size(), rightTokens.size());
        if (coverage >= 0.8d) {
            return coverage;
        }
        return 0d;
    }

    private List<String> tokens(String value) {
        return List.of(value.split(" "))
                .stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .toList();
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace("&nbsp;", " ");
        normalized = normalized.replaceAll("<[^>]+>", " ");
        normalized = HtmlUtils.htmlUnescape(normalized);
        normalized = normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private String cleanHtml(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String cleaned = value.replaceAll("<[^>]+>", " ");
        cleaned = HtmlUtils.htmlUnescape(cleaned);
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private boolean shouldResearchProduct(CatalogProduct product) {
        return CatalogStructure.PESTICIDES.equals(CatalogStructure.normalizeSectionName(product.getCategory()));
    }

    private String buildBatchReportText(ResearchLinksSession session, int start, int end, List<String> lines, boolean hasMore) {
        StringBuilder report = new StringBuilder();
        report.append("🔗 <b>Research ссылок AgroXXI</b>\n");
        report.append("Партия: <b>").append(start).append("–").append(end).append("</b> из <b>").append(session.targets.size()).append("</b>\n");
        report.append("Обновлено ссылок: <b>").append(session.updated).append("</b>, без изменений: <b>").append(session.unchanged)
                .append("</b>, не найдено: <b>").append(session.unresolved).append("</b>, ошибок: <b>").append(session.failed).append("</b>\n\n");
        lines.forEach(line -> report.append(line).append("\n"));
        if (hasMore) {
            report.append("\nНажмите <b>«Продолжить до конца»</b>, чтобы обработать все оставшиеся партии без промежуточных отчетов.");
        }
        return report.toString().trim();
    }

    private String buildFinalSummary(ResearchLinksSession session) {
        StringBuilder summary = new StringBuilder();
        summary.append("🧠 <b>Research ссылок AgroXXI завершен</b>\n\n");
        summary.append("• Проверено пестицидов: <b>").append(session.targets.size()).append("</b>\n");
        summary.append("• Обновлено ссылок: <b>").append(session.updated).append("</b>\n");
        summary.append("• Без изменений: <b>").append(session.unchanged).append("</b>\n");
        summary.append("• Не найдено: <b>").append(session.unresolved).append("</b>\n");
        summary.append("• Ошибок: <b>").append(session.failed).append("</b>\n");
        if (!session.unresolvedProducts.isEmpty()) {
            summary.append("• Без совпадения: <b>")
                    .append(String.join(", ", session.unresolvedProducts.stream().limit(8).toList()))
                    .append(session.unresolvedProducts.size() > 8 ? " и еще " + (session.unresolvedProducts.size() - 8) : "")
                    .append("</b>\n");
        }
        if (!session.failedProducts.isEmpty()) {
            summary.append("• Проблемные позиции: <b>")
                    .append(String.join(", ", session.failedProducts.stream().limit(8).toList()))
                    .append(session.failedProducts.size() > 8 ? " и еще " + (session.failedProducts.size() - 8) : "")
                    .append("</b>\n");
        }
        return summary.toString().trim();
    }

    private void waitForBitrixSyncIfNeeded() {
        BitrixSyncService bitrixSyncService = bitrixSyncServiceProvider.getIfAvailable();
        if (bitrixSyncService == null || !bitrixSyncService.isSyncInProgress()) {
            return;
        }
        log.info("Research links is waiting for Bitrix sync to finish");
        long deadline = System.currentTimeMillis() + BITRIX_SYNC_WAIT_TIMEOUT.toMillis();
        while (bitrixSyncService.isSyncInProgress() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(BITRIX_SYNC_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Researchlinks прерван во время ожидания завершения синхронизации Bitrix.", e);
            }
        }
        if (bitrixSyncService.isSyncInProgress()) {
            throw new IllegalStateException("Bitrix синхронизация все еще выполняется. Повторите /researchlinks чуть позже.");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record BatchReport(String text, boolean hasMore) {
    }

    private record MatchResult(String url, String title, double score) {
        private static MatchResult empty() {
            return new MatchResult("", "", 0d);
        }
    }

    private static final class ResearchLinksSession {
        private final Long initiatedBy;
        private final List<CatalogProduct> targets;
        private final AtomicBoolean processing = new AtomicBoolean(false);
        private final List<String> unresolvedProducts = new ArrayList<>();
        private final List<String> failedProducts = new ArrayList<>();
        private int processed;
        private int updated;
        private int unchanged;
        private int unresolved;
        private int failed;
        private boolean stopped;

        private ResearchLinksSession(Long initiatedBy, List<CatalogProduct> targets) {
            this.initiatedBy = initiatedBy;
            this.targets = targets;
        }
    }
}
