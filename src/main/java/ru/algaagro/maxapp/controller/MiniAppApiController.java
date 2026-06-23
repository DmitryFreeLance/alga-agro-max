package ru.algaagro.maxapp.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.algaagro.maxapp.config.AppProperties;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.service.MaxApiClient;
import ru.algaagro.maxapp.service.OrderService;
import ru.algaagro.maxapp.service.ProductService;
import ru.algaagro.maxapp.service.UserService;

@Validated
@RestController
@RequestMapping("/api")
public class MiniAppApiController {

    private final ProductService productService;
    private final OrderService orderService;
    private final MaxApiClient maxApiClient;
    private final AppProperties appProperties;
    private final UserService userService;

    public MiniAppApiController(ProductService productService, OrderService orderService, MaxApiClient maxApiClient, AppProperties appProperties, UserService userService) {
        this.productService = productService;
        this.orderService = orderService;
        this.maxApiClient = maxApiClient;
        this.appProperties = appProperties;
        this.userService = userService;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "company", "ООО «АЛГА АГРО»",
                "miniAppUrl", appProperties.getMiniAppUrl(),
                "totalProducts", productService.getActiveProducts().size()
        );
    }

    @GetMapping("/catalog/products")
    public List<Map<String, Object>> products(
            @RequestParam(required = false) String culture,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "name") String sort
    ) {
        return productService.findFiltered(culture, category, search, tag, sort).stream()
                .map(productService::toMiniAppDto)
                .toList();
    }

    @GetMapping("/catalog/filters")
    public Map<String, Object> filters() {
        return productService.buildFilterSummary();
    }

    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        CatalogOrder order = orderService.createOrder(new OrderService.CreateOrderCommand(
                request.maxUserId(),
                request.name(),
                request.phone(),
                request.company(),
                request.comment(),
                request.culture(),
                request.deliveryNote(),
                request.items().stream().map(item -> new OrderService.CreateOrderItem(item.productId(), item.quantity())).toList()
        ));
        String summary = orderService.buildAdminSummary(order);
        userService.findAdminUserIds().forEach(adminId -> maxApiClient.sendToUser(adminId, summary, null, "html"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("orderCode", order.getPublicCode());
        response.put("message", "Заказ отправлен администраторам.");
        response.put("adminSummary", summary);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam Long maxUserId) {
        var user = userService.findByMaxUserId(maxUserId).orElse(null);
        return Map.of(
                "maxUserId", maxUserId,
                "displayName", user == null ? "Пользователь MAX" : user.getDisplayName(),
                "username", user == null ? "" : user.getUsername(),
                "admin", user != null && user.isAdmin(),
                "ordersCount", orderService.listOrdersForUser(maxUserId).size()
        );
    }

    @GetMapping("/profile/orders")
    public List<Map<String, Object>> profileOrders(@RequestParam Long maxUserId) {
        return orderService.listOrdersForUser(maxUserId).stream()
                .map(orderService::toDto)
                .toList();
    }

    @GetMapping("/admin/orders")
    public List<Map<String, Object>> adminOrders(@RequestParam Long maxUserId) {
        ensureAdmin(maxUserId);
        return orderService.listOrders(0, 200).getContent().stream()
                .map(orderService::toDto)
                .toList();
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

    private void ensureAdmin(Long maxUserId) {
        if (maxUserId == null || !userService.isAdmin(maxUserId)) {
            throw new IllegalStateException("Недостаточно прав");
        }
    }

    public record CreateOrderRequest(
            Long maxUserId,
            @NotBlank String name,
            @NotBlank String phone,
            String company,
            String comment,
            String culture,
            String deliveryNote,
            @NotEmpty List<CreateOrderItemRequest> items
    ) {
    }

    public record CreateOrderItemRequest(
            Long productId,
            @DecimalMin("0.001") BigDecimal quantity
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
}
