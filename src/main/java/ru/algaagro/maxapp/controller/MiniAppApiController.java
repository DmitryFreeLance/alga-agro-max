package ru.algaagro.maxapp.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
}
