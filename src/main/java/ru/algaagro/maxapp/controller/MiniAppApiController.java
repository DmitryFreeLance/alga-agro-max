package ru.algaagro.maxapp.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.AppUser;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.model.CatalogProduct;
import ru.algaagro.maxapp.service.BitrixSyncService;
import ru.algaagro.maxapp.service.BroadcastService;
import ru.algaagro.maxapp.service.FileStorageService;
import ru.algaagro.maxapp.service.ManufacturerService;
import ru.algaagro.maxapp.service.MaxApiClient;
import ru.algaagro.maxapp.service.OrderService;
import ru.algaagro.maxapp.service.ProductService;
import ru.algaagro.maxapp.service.UserService;
import ru.algaagro.maxapp.util.JsonHelper;

@Validated
@RestController
@RequestMapping("/api")
public class MiniAppApiController {

    private final ProductService productService;
    private final OrderService orderService;
    private final MaxApiClient maxApiClient;
    private final AppProperties appProperties;
    private final UserService userService;
    private final BitrixSyncService bitrixSyncService;
    private final ManufacturerService manufacturerService;
    private final BroadcastService broadcastService;
    private final FileStorageService fileStorageService;
    private final JsonHelper jsonHelper;

    public MiniAppApiController(
            ProductService productService,
            OrderService orderService,
            MaxApiClient maxApiClient,
            AppProperties appProperties,
            UserService userService,
            BitrixSyncService bitrixSyncService,
            ManufacturerService manufacturerService,
            BroadcastService broadcastService,
            FileStorageService fileStorageService,
            JsonHelper jsonHelper
    ) {
        this.productService = productService;
        this.orderService = orderService;
        this.maxApiClient = maxApiClient;
        this.appProperties = appProperties;
        this.userService = userService;
        this.bitrixSyncService = bitrixSyncService;
        this.manufacturerService = manufacturerService;
        this.broadcastService = broadcastService;
        this.fileStorageService = fileStorageService;
        this.jsonHelper = jsonHelper;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "company", "ООО «АЛГА АГРО»",
                "miniAppUrl", appProperties.getMiniAppUrl(),
                "totalProducts", productService.getActiveProducts().size(),
                "managerName", "Марат",
                "managerPhone", "+7 917 595-51-43",
                "managerMaxLink", appProperties.getManagerDeepLink(),
                "managerExternalLink", appProperties.getManagerContactUrl()
        );
    }

    @GetMapping("/catalog/sections")
    public List<Map<String, Object>> sections() {
        return productService.getActiveProducts().stream()
                .collect(Collectors.groupingBy(
                        product -> product.getCategory() == null || product.getCategory().isBlank() ? "Прочее" : product.getCategory().trim(),
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .entrySet().stream()
                .map(entry -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("name", entry.getKey());
                    dto.put("description", buildSectionDescription(entry.getKey()));
                    dto.put("productsCount", entry.getValue().size());
                    return dto;
                })
                .sorted(Comparator.comparing(item -> String.valueOf(item.get("name")), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/catalog/products")
    public List<Map<String, Object>> products(
            @RequestParam(required = false) String culture,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String season,
            @RequestParam(defaultValue = "name") String sort
    ) {
        return productService.findFiltered(culture, category, search, tag, season, sort).stream()
                .map(productService::toMiniAppDto)
                .toList();
    }

    @GetMapping("/catalog/filters")
    public Map<String, Object> filters(@RequestParam(required = false) String culture) {
        return productService.buildFilterSummary(culture);
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        CatalogOrder order = orderService.createOrder(new OrderService.CreateOrderCommand(
                request.maxUserId(),
                request.name(),
                request.phone(),
                request.company(),
                request.farmName(),
                request.inn(),
                request.email(),
                request.deliveryAddress(),
                request.comment(),
                request.culture(),
                request.deliveryNote(),
                request.attachments(),
                request.items().stream().map(item -> new OrderService.CreateOrderItem(item.productId(), item.quantity())).toList()
        ));
        String summary = orderService.buildAdminSummary(order);
        userService.findAdminUserIds().forEach(adminId -> maxApiClient.sendToUser(adminId, summary, null, "html"));
        if (order.getCustomerMaxUserId() != null) {
            userService.clearClientState(order.getCustomerMaxUserId());
            maxApiClient.sendToUser(order.getCustomerMaxUserId(), orderService.buildCustomerSummary(order), null, "html");
        }
        try {
            bitrixSyncService.syncOrderToLead(order);
        } catch (RuntimeException e) {
            // Keep checkout successful even if CRM sync is temporarily unavailable.
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderCode", order.getPublicCode());
        response.put("message", "Заказ отправлен администраторам.");
        response.put("adminSummary", summary);
        response.put("bitrixLeadId", order.getBitrixLeadId());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/uploads/order-attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadOrderAttachment(
            @RequestParam Long maxUserId,
            @RequestParam("file") MultipartFile file
    ) {
        if (maxUserId == null) {
            throw new IllegalArgumentException("Не удалось определить пользователя");
        }
        return fileStorageService.store("order-attachments", file);
    }

    @PostMapping(value = "/uploads/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadMedia(
            @RequestParam Long maxUserId,
            @RequestParam("file") MultipartFile file
    ) {
        if (maxUserId == null) {
            throw new IllegalArgumentException("Не удалось определить пользователя");
        }
        return fileStorageService.store("media", file);
    }

    @PostMapping("/profile/state")
    public Map<String, Object> syncProfileState(@RequestBody ClientStateRequest request) {
        if (request.maxUserId() == null) {
            throw new IllegalArgumentException("Не удалось определить пользователя");
        }
        AppUser user = userService.syncClientState(new UserService.ClientStateCommand(
                request.maxUserId(),
                request.displayName(),
                request.username(),
                request.cartItems(),
                request.checkoutDraft()
        ));
        return Map.of(
                "saved", true,
                "maxUserId", user.getMaxUserId()
        );
    }

    @GetMapping("/files/{scope}/{storedName}")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable String scope,
            @PathVariable String storedName,
            @RequestParam(required = false) String filename
    ) {
        Resource resource = fileStorageService.loadAsResource(scope, storedName);
        String resolvedFilename = filename == null || filename.isBlank() ? resource.getFilename() : filename;
        MediaType contentType = MediaTypeFactory.getMediaType(resolvedFilename)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        boolean forceDownload = !"media".equalsIgnoreCase(scope);
        ContentDisposition disposition = forceDownload
                ? ContentDisposition.attachment().filename(resolvedFilename, StandardCharsets.UTF_8).build()
                : ContentDisposition.inline().filename(resolvedFilename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam Long maxUserId) {
        var user = userService.findByMaxUserId(maxUserId).orElse(null);
        CatalogOrder latestOrder = orderService.listOrdersForUser(maxUserId).stream().findFirst().orElse(null);
        Map<String, Object> draftCheckout = user == null
                ? Map.of()
                : jsonHelper.readMap(user.getCheckoutDraftJson());
        List<Map<String, Object>> savedCart = user == null
                ? List.of()
                : jsonHelper.readValue(user.getCartJson(), new TypeReference<>() { }, List.of());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("maxUserId", maxUserId);
        response.put("displayName", user == null || user.getDisplayName() == null || user.getDisplayName().isBlank() ? "Пользователь MAX" : user.getDisplayName());
        response.put("username", user == null || user.getUsername() == null ? "" : user.getUsername());
        response.put("admin", user != null && user.isAdmin());
        response.put("ordersCount", orderService.listOrdersForUser(maxUserId).size());
        response.put("phone", latestOrder == null ? "" : latestOrder.getCustomerPhone());
        response.put("email", latestOrder == null ? "" : latestOrder.getCustomerEmail());
        response.put("farmName", latestOrder == null ? "" : latestOrder.getCustomerFarmName());
        response.put("inn", latestOrder == null ? "" : latestOrder.getCustomerInn());
        response.put("managerName", "Марат");
        response.put("managerPhone", "+7 917 595-51-43");
        response.put("managerMaxLink", appProperties.getManagerDeepLink());
        response.put("managerExternalLink", appProperties.getManagerContactUrl());
        response.put("draftCheckout", draftCheckout);
        response.put("savedCart", savedCart);
        response.put("lastSeenAt", user == null ? null : user.getLastSeenAt());
        return response;
    }

    @GetMapping("/profile/orders")
    public List<Map<String, Object>> profileOrders(@RequestParam Long maxUserId) {
        return orderService.listOrdersForUser(maxUserId).stream()
                .map(orderService::toDto)
                .toList();
    }

    @GetMapping("/admin/dashboard")
    public Map<String, Object> adminDashboard(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return orderService.buildAdminDashboard(
                userService.countUsers(),
                userService.countUsersCreatedThisMonth(),
                productService.getActiveProducts().size()
        );
    }

    @GetMapping("/admin/orders")
    public List<Map<String, Object>> adminOrders(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return orderService.listOrders(0, 200).getContent().stream()
                .map(orderService::toDto)
                .toList();
    }

    @GetMapping("/admin/customers")
    public List<Map<String, Object>> adminCustomers(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        Map<Long, List<CatalogOrder>> ordersByUser = orderService.listOrders(0, 2000).getContent().stream()
                .filter(order -> order.getCustomerMaxUserId() != null)
                .collect(Collectors.groupingBy(CatalogOrder::getCustomerMaxUserId));
        return userService.listCustomersByLastSeen().stream()
                .filter(user -> !user.isAdmin())
                .map(user -> toAdminCustomerDto(user, ordersByUser.getOrDefault(user.getMaxUserId(), List.of())))
                .toList();
    }

    @PutMapping("/admin/orders/{id}/status")
    public Map<String, Object> updateOrderStatus(@RequestParam Long maxUserId, @PathVariable Long id, @RequestBody OrderStatusRequest request) {
        ensureAdmin(maxUserId);
        return orderService.toDto(orderService.updateStatus(id, request.status()));
    }

    @GetMapping("/admin/products")
    public List<Map<String, Object>> adminProducts(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return productService.getAdminProducts();
    }

    @PostMapping("/admin/products")
    public Map<String, Object> createAdminProduct(@RequestParam Long maxUserId, @RequestBody AdminProductRequest request) {
        ensureAdmin(maxUserId);
        return productService.toAdminDto(productService.createManualProduct(request.toPayload()));
    }

    @PutMapping("/admin/products/{id}")
    public Map<String, Object> updateAdminProduct(@RequestParam Long maxUserId, @PathVariable Long id, @RequestBody AdminProductRequest request) {
        ensureAdmin(maxUserId);
        return productService.toAdminDto(productService.updateProduct(id, request.toPayload()));
    }

    @DeleteMapping("/admin/products/{id}")
    public Map<String, Object> deleteAdminProduct(@RequestParam Long maxUserId, @PathVariable Long id) {
        ensureAdmin(maxUserId);
        productService.deleteProduct(id);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/admin/manufacturers")
    public List<Map<String, Object>> adminManufacturers(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return manufacturerService.listManufacturers(productService.getActiveProducts());
    }

    @PostMapping("/admin/manufacturers")
    public Map<String, Object> createManufacturer(@RequestParam Long maxUserId, @RequestBody ManufacturerRequest request) {
        ensureAdmin(maxUserId);
        var manufacturer = manufacturerService.createManufacturer(request.name());
        return Map.of("id", manufacturer.getId(), "name", manufacturer.getName(), "productsCount", 0);
    }

    @PutMapping("/admin/manufacturers/{id}")
    public Map<String, Object> updateManufacturer(@RequestParam Long maxUserId, @PathVariable Long id, @RequestBody ManufacturerRequest request) {
        ensureAdmin(maxUserId);
        manufacturerService.renameManufacturer(id, request.name(), productService.getActiveProducts(), productService);
        return Map.of("updated", true, "id", id);
    }

    @DeleteMapping("/admin/manufacturers/{id}")
    public Map<String, Object> deleteManufacturer(@RequestParam Long maxUserId, @PathVariable Long id) {
        ensureAdmin(maxUserId);
        manufacturerService.deleteManufacturer(id, productService.getActiveProducts());
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/admin/broadcasts")
    public Map<String, Object> broadcastOverview(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return Map.of(
                "stats", broadcastService.getStats(),
                "history", broadcastService.history()
        );
    }

    @PostMapping("/admin/broadcasts")
    public Map<String, Object> sendBroadcast(@RequestParam Long maxUserId, @RequestBody BroadcastRequest request) {
        ensureAdmin(maxUserId);
        return broadcastService.sendBroadcast(request.text(), request.imageUrl());
    }

    private void ensureAdmin(Long maxUserId) {
        if (maxUserId == null || !userService.isAdmin(maxUserId)) {
            throw new IllegalStateException("Недостаточно прав");
        }
    }

    private Map<String, Object> toAdminCustomerDto(AppUser user, List<CatalogOrder> orders) {
        Map<String, Object> draft = jsonHelper.readMap(user.getCheckoutDraftJson());
        List<Map<String, Object>> rawCartItems = jsonHelper.readValue(
                user.getCartJson(),
                new TypeReference<List<Map<String, Object>>>() { },
                List.of()
        );
        List<Map<String, Object>> cartItems = rawCartItems.stream()
                .map(this::toAdminCartItemDto)
                .filter(Objects::nonNull)
                .toList();
        BigDecimal cartTotal = cartItems.stream()
                .map(item -> toBigDecimal(item.get("totalPrice")))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean requestPricePresent = cartItems.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("priceOnRequest")));
        long cartUnits = cartItems.stream()
                .map(item -> toBigDecimal(item.get("quantity")))
                .filter(Objects::nonNull)
                .map(BigDecimal::longValue)
                .reduce(0L, Long::sum);
        CatalogOrder latestOrder = orders.stream().findFirst().orElse(null);
        long completedOrders = orders.stream().filter(order -> "COMPLETED".equals(order.getStatus().name())).count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("maxUserId", user.getMaxUserId());
        response.put("displayName", user.getDisplayName());
        response.put("username", user.getUsername());
        response.put("lastSeenAt", user.getLastSeenAt());
        response.put("cartUpdatedAt", user.getCartUpdatedAt());
        response.put("draftUpdatedAt", user.getCheckoutDraftUpdatedAt());
        response.put("ordersCount", orders.size());
        response.put("completedOrdersCount", completedOrders);
        response.put("latestOrderCode", latestOrder == null ? "" : latestOrder.getPublicCode());
        response.put("latestOrderAt", latestOrder == null ? null : latestOrder.getCreatedAt());
        response.put("draftCheckout", draft);
        response.put("draftFilled", hasDraftContent(draft));
        response.put("cartItems", cartItems);
        response.put("cartItemsCount", cartItems.size());
        response.put("cartUnits", cartUnits);
        response.put("cartTotal", cartTotal);
        response.put("cartContainsRequestPrice", requestPricePresent);
        response.put("orders", orders.stream().limit(5).map(orderService::toDto).toList());
        response.put("contactPhone", firstNonBlank(
                stringValue(draft.get("phone")),
                latestOrder == null ? "" : latestOrder.getCustomerPhone()
        ));
        response.put("contactEmail", firstNonBlank(
                stringValue(draft.get("email")),
                latestOrder == null ? "" : latestOrder.getCustomerEmail()
        ));
        response.put("organization", firstNonBlank(
                stringValue(draft.get("organization")),
                latestOrder == null ? "" : latestOrder.getCustomerCompany()
        ));
        response.put("farmName", firstNonBlank(
                stringValue(draft.get("farmName")),
                latestOrder == null ? "" : latestOrder.getCustomerFarmName()
        ));
        response.put("inn", firstNonBlank(
                stringValue(draft.get("inn")),
                latestOrder == null ? "" : latestOrder.getCustomerInn()
        ));
        response.put("deliveryAddress", firstNonBlank(
                stringValue(draft.get("address")),
                latestOrder == null ? "" : latestOrder.getDeliveryAddress()
        ));
        response.put("abandonedDraft", hasDraftContent(draft) || !cartItems.isEmpty());
        return response;
    }

    private Map<String, Object> toAdminCartItemDto(Map<String, Object> rawItem) {
        Long productId = toLong(rawItem.get("productId"));
        BigDecimal quantity = toBigDecimal(rawItem.get("quantity"));
        if (productId == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        CatalogProduct product = productService.findById(productId).orElse(null);
        BigDecimal unitPrice = product == null ? null : product.getPrice();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("productId", productId);
        response.put("quantity", quantity);
        response.put("name", product == null ? ("Товар #" + productId) : product.getName());
        response.put("brand", product == null ? "" : product.getBrand());
        response.put("unitName", product == null ? "шт" : product.getUnitName());
        response.put("packageDescription", product == null ? "" : product.getPackageDescription());
        response.put("unitPrice", unitPrice);
        response.put("priceOnRequest", unitPrice == null);
        response.put("totalPrice", unitPrice == null ? null : unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP));
        return response;
    }

    private boolean hasDraftContent(Map<String, Object> draft) {
        if (draft == null || draft.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : draft.entrySet()) {
            if ("attachments".equals(entry.getKey()) && entry.getValue() instanceof List<?> attachments && !attachments.isEmpty()) {
                return true;
            }
            if (entry.getValue() instanceof String value && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim().replace(",", ".");
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public record CreateOrderRequest(
            Long maxUserId,
            @NotBlank String name,
            @NotBlank String phone,
            String company,
            String farmName,
            String inn,
            @Email @NotBlank String email,
            @NotBlank String deliveryAddress,
            String comment,
            String culture,
            String deliveryNote,
            List<Map<String, Object>> attachments,
            @NotEmpty List<CreateOrderItemRequest> items
    ) {
    }

    public record CreateOrderItemRequest(
            Long productId,
            @DecimalMin("0.001") BigDecimal quantity
    ) {
    }

    public record ManufacturerRequest(@NotBlank String name) {
    }

    public record OrderStatusRequest(@NotBlank String status) {
    }

    public record BroadcastRequest(@NotBlank String text, String imageUrl) {
    }

    public record ClientStateRequest(
            Long maxUserId,
            String displayName,
            String username,
            List<Map<String, Object>> cartItems,
            Map<String, Object> checkoutDraft
    ) {
    }

    public record AdminProductRequest(
            String externalId,
            String sourceFile,
            String sku,
            String name,
            String description,
            String brand,
            String category,
            String subcategory,
            String itemType,
            String unitName,
            BigDecimal price,
            BigDecimal stockQuantity,
            String packageType,
            String packageDescription,
            BigDecimal minOrderQuantity,
            BigDecimal orderStep,
            String cultures,
            String purposes,
            String tags,
            Map<String, Object> filterMap,
            Map<String, Object> rawData,
            Boolean active
    ) {
        ProductService.AdminProductPayload toPayload() {
            return new ProductService.AdminProductPayload(
                    externalId,
                    sourceFile,
                    sku,
                    name,
                    description,
                    brand,
                    category,
                    subcategory,
                    itemType,
                    unitName,
                    price,
                    stockQuantity,
                    packageType,
                    packageDescription,
                    minOrderQuantity,
                    orderStep,
                    splitCsv(cultures),
                    splitCsv(purposes),
                    splitCsv(tags),
                    filterMap,
                    rawData,
                    active
            );
        }

        private static List<String> splitCsv(String value) {
            if (value == null || value.isBlank()) {
                return List.of();
            }
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
    }

    private String buildSectionDescription(String sectionName) {
        String normalized = sectionName == null ? "" : sectionName.toLowerCase();
        if (normalized.contains("герб")) {
            return "Защита от сорняков";
        }
        if (normalized.contains("фунг")) {
            return "Защита от болезней";
        }
        if (normalized.contains("инсект")) {
            return "Защита от вредителей";
        }
        if (normalized.contains("сем")) {
            return "Зерновые, масличные, бобовые";
        }
        if (normalized.contains("агрохим") || normalized.contains("удобр") || normalized.contains("питан")) {
            return "Удобрения и стимуляторы";
        }
        if (normalized.contains("мелиор")) {
            return "Известкование почв";
        }
        return "Актуальные позиции каталога";
    }
}
