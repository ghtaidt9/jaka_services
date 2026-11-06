package com.jaka.notification_service.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.jaka.notification_service.dto.EventEnvelope;
import com.jaka.notification_service.dto.InventoryRejected;
import com.jaka.notification_service.dto.OrderCreated;
import com.jaka.notification_service.dto.PaymentCompleted;
import com.jaka.notification_service.dto.PaymentFailed;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class NotificationTemplates {
    private final ObjectMapper json;

    public record Message(Long orderId, String customerName, String subject, String body) {
    }

    public Optional<Message> render(EventEnvelope<JsonNode> event) {
        return switch (event.eventType()) {
            case "OrderCreated" -> {
                var e = json.treeToValue(event.payload(), OrderCreated.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Đơn " + e.orderNumber() + " đã được tạo",
                        "Chào " + e.customerName() + ", chúng tôi đã nhận đơn " + e.orderNumber()
                                + ": " + e.quantity() + " × " + e.productSku() + ", tổng " + e.totalAmount() + " "
                                + e.currency() + "."));
            }
            case "InventoryRejected" -> {
                var e = json.treeToValue(event.payload(), InventoryRejected.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Đơn #" + e.orderId() + ": sản phẩm " + e.productSku() + " đã hết hàng",
                        "Chào " + e.customerName() + ", rất tiếc sản phẩm " + e.productSku()
                                + " không đủ hàng. Đơn đã bị hủy."));
            }
            case "PaymentCompleted" -> {
                var e = json.treeToValue(event.payload(), PaymentCompleted.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Thanh toán đơn #" + e.orderId() + " thành công",
                        "Chào " + e.customerName() + ", đã thu " + e.amount() + " " + e.currency()
                                + " (mã " + e.paymentReference() + ")."));
            }
            case "PaymentFailed" -> {
                var e = json.treeToValue(event.payload(), PaymentFailed.class);
                yield Optional.of(new Message(e.orderId(), e.customerName(),
                        "Thanh toán đơn #" + e.orderId() + " thất bại",
                        "Chào " + e.customerName() + ", thanh toán " + e.amount() + " " + e.currency()
                                + " bị từ chối (" + e.reason() + "). Hàng đã được trả lại kho."));
            }
            default -> Optional.empty(); // InventoryReserved, InventoryReleased, event lạ → không báo
        };
    }
}
