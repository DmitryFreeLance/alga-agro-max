package ru.algaagro.maxapp.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.algaagro.maxapp.model.CatalogOrder;
import ru.algaagro.maxapp.model.CatalogOrderItem;
import ru.algaagro.maxapp.repository.CatalogOrderRepository;
import ru.algaagro.maxapp.util.JsonHelper;
import ru.algaagro.maxapp.util.TextUtils;

@Service
public class OrderService {

    private final CatalogOrderRepository catalogOrderRepository;
    private final ProductService productService;
    private final JsonHelper jsonHelper;

    public OrderService(CatalogOrderRepository catalogOrderRepository, ProductService productService, JsonHelper jsonHelper) {
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
        order.setComment(command.comment());

        BigDecimal total = BigDecimal.ZERO;
        for (CreateOrderItem itemCommand : command.items()) {
            var product = productService.findById(itemCommand.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + itemCommand.productId()));
            CatalogOrderItem item = new CatalogOrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setQuantity(itemCommand.quantity());
            item.setUnitPrice(product.getPrice() == null ? BigDecimal.ZERO : product.getPrice());
            total = total.add(item.getUnitPrice().multiply(item.getQuantity()));
            order.addItem(item);
        }
        order.setTotalPrice(total);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("culture", command.culture());
        payload.put("deliveryNote", command.deliveryNote());
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
        if (order.getComment() != null && !order.getComment().isBlank()) {
            builder.append("💬 ").append(order.getComment()).append("\n");
        }
        builder.append("\n");
        order.getItems().forEach(item -> builder.append("• ")
                .append(item.getProductName())
                .append(" × ")
                .append(item.getQuantity().stripTrailingZeros().toPlainString())
                .append(" = ")
                .append(TextUtils.formatPrice(item.getUnitPrice().multiply(item.getQuantity())))
                .append("\n"));
        builder.append("\n💳 Итого: ").append(TextUtils.formatPrice(order.getTotalPrice()));
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
                .append("\n"));
        builder.append("\n💳 Итого: ").append(TextUtils.formatPrice(order.getTotalPrice()))
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
        dto.put("comment", order.getComment());
        dto.put("status", order.getStatus().name());
        dto.put("totalPrice", order.getTotalPrice());
        dto.put("createdAt", order.getCreatedAt());
        dto.put("items", order.getItems().stream().map(item -> Map.of(
                "productId", item.getProductId(),
                "productName", item.getProductName(),
                "quantity", item.getQuantity(),
                "unitPrice", item.getUnitPrice()
        )).toList());
        return dto;
    }

    public record CreateOrderCommand(
            Long maxUserId,
            String name,
            String phone,
            String company,
            String comment,
            String culture,
            String deliveryNote,
            List<CreateOrderItem> items
    ) {
    }

    public record CreateOrderItem(Long productId, BigDecimal quantity) {
    }
}
