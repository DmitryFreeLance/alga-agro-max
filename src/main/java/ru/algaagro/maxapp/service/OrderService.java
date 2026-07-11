package ru.algaagro.maxapp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.model.CatalogOrderItem;
import ru.algaagro.maxapp.model.OrderStatus;
import ru.algaagro.maxapp.repository.CatalogOrderRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class OrderService {

    private final CatalogOrderRepository catalogOrderRepository;
    private final ProductService productService;
    private final JsonHelper jsonHelper;

    public OrderService(
            CatalogOrderRepository catalogOrderRepository,
            ProductService productService,
            JsonHelper jsonHelper
    ) {
        this.catalogOrderRepository = catalogOrderRepository;
        this.productService = productService;
        this.jsonHelper = jsonHelper;
    }

    @Transactional
    public CatalogOrder createOrder(CreateOrderCommand command) {
        CatalogOrder order = new CatalogOrder();
        order.setPublicCode(generatePublicCode());
        order.setCustomerMaxUserId(command.maxUserId());
        order.setCustomerName(command.name());
        order.setCustomerPhone(command.phone());
        order.setCustomerCompany(command.company());
        order.setCustomerFarmName(command.farmName());
        order.setCustomerInn(command.inn());
        order.setCustomerEmail(command.email());
        order.setDeliveryAddress(command.deliveryAddress());
        order.setComment(command.comment());
        order.setAttachmentsJson(jsonHelper.writeValue(command.attachments() == null ? List.of() : command.attachments()));
        order.setCurrencyCode(normalizeOrderCurrencyCode(command.currencyCode()));

        BigDecimal total = BigDecimal.ZERO;
        List<String> itemCurrencies = new ArrayList<>();
        for (CreateOrderItem itemCommand : command.items()) {
            var product = productService.findById(itemCommand.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + itemCommand.productId()));
            productService.validateOrderQuantity(product, itemCommand.quantity());
            ProductService.PriceQuote priceQuote = productService.resolvePriceQuote(product, itemCommand.selectedReproduction(), command.currencyCode());
            CatalogOrderItem item = new CatalogOrderItem();
            item.setProductId(product.getId());
            item.setProductName(productService.buildVariantProductName(product, itemCommand.selectedReproduction()));
            item.setQuantity(itemCommand.quantity());
            item.setUnitPrice(priceQuote.amount());
            item.setCurrencyCode(priceQuote.currencyCode());
            itemCurrencies.add(priceQuote.currencyCode());
            total = total.add(priceQuote.amount().multiply(item.getQuantity()));
            order.addItem(item);
        }
        boolean mixedCurrencies = itemCurrencies.stream().filter(Objects::nonNull).distinct().count() > 1;
        order.setCurrencyCode(mixedCurrencies ? "MIXED" : normalizeOrderCurrencyCode(itemCurrencies.isEmpty() ? command.currencyCode() : itemCurrencies.get(0)));
        order.setTotalPrice(mixedCurrencies ? BigDecimal.ZERO : total);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("culture", command.culture());
        payload.put("deliveryNote", command.deliveryNote());
        payload.put("email", command.email());
        payload.put("farmName", command.farmName());
        payload.put("inn", command.inn());
        payload.put("deliveryAddress", command.deliveryAddress());
        payload.put("attachments", command.attachments());
        payload.put("currencyCode", order.getCurrencyCode());
        payload.put("items", command.items());
        order.setPayloadJson(jsonHelper.writeValue(payload));
        return catalogOrderRepository.save(order);
    }

    public Page<CatalogOrder> listOrders(int page, int size) {
        return catalogOrderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), Math.max(1, size)));
    }

    public long countOrders() {
        return catalogOrderRepository.count();
    }

    public List<CatalogOrder> listOrdersForUser(Long maxUserId) {
        if (maxUserId == null) {
            return List.of();
        }
        return catalogOrderRepository.findAllByCustomerMaxUserIdOrderByCreatedAtDesc(maxUserId);
    }

    public String buildAdminSummary(CatalogOrder order) {
        StringBuilder builder = new StringBuilder();
        builder.append("🛒 <b>Новый заказ ").append(order.getPublicCode()).append("</b>\n")
                .append("👤 ").append(order.getCustomerName()).append("\n")
                .append("📞 ").append(order.getCustomerPhone()).append("\n");
        if (order.getCustomerCompany() != null && !order.getCustomerCompany().isBlank()) {
            builder.append("🏢 ").append(order.getCustomerCompany()).append("\n");
        }
        if (order.getCustomerFarmName() != null && !order.getCustomerFarmName().isBlank()) {
            builder.append("🌾 Хозяйство: ").append(order.getCustomerFarmName()).append("\n");
        }
        if (order.getCustomerInn() != null && !order.getCustomerInn().isBlank()) {
            builder.append("🧾 ИНН: ").append(order.getCustomerInn()).append("\n");
        }
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            builder.append("✉️ ").append(order.getCustomerEmail()).append("\n");
        }
        if (order.getDeliveryAddress() != null && !order.getDeliveryAddress().isBlank()) {
            builder.append("📍 ").append(order.getDeliveryAddress()).append("\n");
        }
        if (order.getComment() != null && !order.getComment().isBlank()) {
            builder.append("💬 ").append(order.getComment()).append("\n");
        }
        List<Map<String, Object>> attachments = jsonHelper.readValue(order.getAttachmentsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of());
        if (!attachments.isEmpty()) {
            builder.append("📎 Реквизиты: ").append(attachments.size()).append(" файл(а)\n");
            attachments.forEach(attachment -> {
                String url = String.valueOf(attachment.getOrDefault("downloadUrl", ""));
                String name = String.valueOf(attachment.getOrDefault("originalName", attachment.getOrDefault("storedName", "Файл")));
                if (!url.isBlank()) {
                    builder.append("   • ").append(name).append(": ").append(url).append("\n");
                }
            });
        }
        builder.append("\n");
        order.getItems().forEach(item -> builder.append("• ")
                .append(item.getProductName())
                .append(" × ")
                .append(item.getQuantity().stripTrailingZeros().toPlainString())
                .append(" ")
                .append(resolveOrderItemUnitName(item))
                .append(" = ")
                .append(TextUtils.formatPrice(item.getUnitPrice().multiply(item.getQuantity()), item.getCurrencyCode()))
                .append("\n"));
        builder.append("\n💳 Итого: ").append(formatOrderTotal(order));
        return builder.toString();
    }

    public String buildCustomerSummary(CatalogOrder order) {
        StringBuilder builder = new StringBuilder();
        builder.append("✅ <b>Заказ ").append(order.getPublicCode()).append(" принят</b>\n")
                .append("Спасибо за заявку");
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank()) {
            builder.append(", ").append(order.getCustomerName());
        }
        builder.append(".\n\n");
        order.getItems().forEach(item -> builder.append("• ")
                .append(item.getProductName())
                .append(" × ")
                .append(item.getQuantity().stripTrailingZeros().toPlainString())
                .append(" ")
                .append(resolveOrderItemUnitName(item))
                .append("\n"));
        builder.append("\n💳 Итого: ").append(formatOrderTotal(order))
                .append("\n📞 Менеджер свяжется с вами для подтверждения деталей.");
        return builder.toString();
    }

    private String generatePublicCode() {
        return "AG-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddMM")) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    public Map<String, Object> toDto(CatalogOrder order) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", order.getId());
        dto.put("publicCode", order.getPublicCode());
        dto.put("customerMaxUserId", order.getCustomerMaxUserId());
        dto.put("customerName", order.getCustomerName());
        dto.put("customerPhone", order.getCustomerPhone());
        dto.put("customerCompany", order.getCustomerCompany());
        dto.put("customerFarmName", order.getCustomerFarmName());
        dto.put("customerInn", order.getCustomerInn());
        dto.put("customerEmail", order.getCustomerEmail());
        dto.put("deliveryAddress", order.getDeliveryAddress());
        dto.put("comment", order.getComment());
        dto.put("status", order.getStatus().name());
        dto.put("statusLabel", statusLabel(order.getStatus()));
        dto.put("totalPrice", order.getTotalPrice());
        dto.put("currencyCode", order.getCurrencyCode());
        dto.put("createdAt", order.getCreatedAt());
        dto.put("attachments", jsonHelper.readValue(order.getAttachmentsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }, List.of()));
        dto.put("items", order.getItems().stream().map(item -> Map.of(
                "productId", item.getProductId(),
                "productName", item.getProductName(),
                "quantity", item.getQuantity(),
                "unitPrice", item.getUnitPrice(),
                "currencyCode", item.getCurrencyCode(),
                "unitName", resolveOrderItemUnitName(item)
        )).toList());
        return dto;
    }

    private String formatOrderTotal(CatalogOrder order) {
        if (order == null) {
            return "По запросу";
        }
        if ("MIXED".equalsIgnoreCase(order.getCurrencyCode())) {
            return "Смешанная валюта";
        }
        return TextUtils.formatPrice(order.getTotalPrice(), order.getCurrencyCode());
    }

    private String normalizeOrderCurrencyCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        return switch (normalized) {
            case "USD", "EUR", "MIXED" -> normalized;
            default -> "RUB";
        };
    }

    private String resolveOrderItemUnitName(CatalogOrderItem item) {
        return productService.findById(item.getProductId())
                .map(product -> productService.resolveDisplayUnit(product))
                .orElse("ед.");
    }

    @Transactional
    public CatalogOrder updateStatus(Long orderId, String targetStatus) {
        CatalogOrder order = catalogOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Заявка не найдена"));
        OrderStatus nextStatus = OrderStatus.valueOf(targetStatus);
        validateTransition(order.getStatus(), nextStatus);
        order.setStatus(nextStatus);
        return catalogOrderRepository.save(order);
    }

    public Map<String, Object> buildAdminDashboard(long totalUsers, long usersAddedThisMonth, long totalProducts) {
        List<CatalogOrder> latestOrders = listOrders(0, 10).getContent();
        long totalOrders = catalogOrderRepository.count();
        long pendingOrders = catalogOrderRepository.findAll().stream().filter(order -> order.getStatus() == OrderStatus.NEW).count();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("ordersTotal", totalOrders);
        dto.put("ordersPending", pendingOrders);
        dto.put("productsTotal", totalProducts);
        dto.put("usersTotal", totalUsers);
        dto.put("usersAddedThisMonth", usersAddedThisMonth);
        dto.put("latestOrders", latestOrders.stream().map(this::toDto).toList());
        return dto;
    }

    private void validateTransition(OrderStatus current, OrderStatus next) {
        if (current == OrderStatus.CANCELLED || current == OrderStatus.COMPLETED) {
            throw new IllegalArgumentException("Эту заявку больше нельзя изменить");
        }
        if (current == next) {
            return;
        }
        boolean allowed = switch (current) {
            case NEW -> next == OrderStatus.IN_PROGRESS || next == OrderStatus.CANCELLED;
            case IN_PROGRESS -> next == OrderStatus.COMPLETED || next == OrderStatus.CANCELLED;
            default -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("Недопустимый переход статуса");
        }
    }

    private String statusLabel(OrderStatus status) {
        return switch (status) {
            case NEW -> "В ожидании";
            case IN_PROGRESS -> "В работе";
            case COMPLETED -> "Оплачен";
            case CANCELLED -> "Отменён";
        };
    }

    public record CreateOrderCommand(
            Long maxUserId,
            String name,
            String phone,
            String company,
            String farmName,
            String inn,
            String email,
            String deliveryAddress,
            String comment,
            String culture,
            String deliveryNote,
            List<Map<String, Object>> attachments,
            String currencyCode,
            List<CreateOrderItem> items
    ) {
    }

    public record CreateOrderItem(Long productId, BigDecimal quantity, String selectedReproduction) {
    }
}
