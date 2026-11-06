package com.jaka.inventory_service.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jaka.inventory_service.dto.InventoryRejected;
import com.jaka.inventory_service.dto.InventoryReleased;
import com.jaka.inventory_service.dto.InventoryReserved;
import com.jaka.inventory_service.dto.OrderCreated;
import com.jaka.inventory_service.dto.PaymentCompleted;
import com.jaka.inventory_service.dto.PaymentFailed;
import com.jaka.inventory_service.entity.Product;
import com.jaka.inventory_service.entity.Reservation;
import com.jaka.inventory_service.entity.ReservationStatus;
import com.jaka.inventory_service.exception.ProductNotFoundException;
import com.jaka.inventory_service.exception.ReservationNotFoundException;
import com.jaka.inventory_service.repository.ProductRepository;
import com.jaka.inventory_service.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxWriter outboxWriter;

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Optional<Product> getProduct(String sku) {
        return productRepository.findById(sku);
    }

    public List<Reservation> getAllReservations() {
        return reservationRepository.findAll();
    }

    @Transactional
    public void reserve(OrderCreated order) {
        Product product = productRepository.findById(order.productSku())
                .orElseThrow(() -> new ProductNotFoundException(order.productSku()));
        if (product.getAvailable() < order.quantity()) {
            InventoryRejected inventoryRejected = new InventoryRejected(order.orderId(), order.productSku(),
                    order.quantity(),
                    order.customerName(), order.totalAmount(), "Insufficient stock");

            outboxWriter.write("InventoryRejected", inventoryRejected.orderId().toString(), inventoryRejected);
            return;
        }
        product.setAvailable(product.getAvailable() - order.quantity());
        product.setReserved(product.getReserved() + order.quantity());
        reservationRepository.save(
                new Reservation(order.orderId(), order.productSku(), order.quantity(), ReservationStatus.RESERVED));

        InventoryReserved inventoryReserved = new InventoryReserved(order.orderId(), order.productSku(),
                order.quantity(),
                order.customerName(), order.totalAmount(), order.currency());
        outboxWriter.write("InventoryReserved", inventoryReserved.orderId().toString(), inventoryReserved);
    }

    // service/InventoryService.java — thêm 2 method
    @Transactional
    public void commit(PaymentCompleted payment) {
        Reservation r = reservationRepository.findById(payment.orderId())
                .orElseThrow(() -> new ReservationNotFoundException(payment.orderId().toString()));
        if (r.getStatus() != ReservationStatus.RESERVED) {
            return; // đã COMMITTED/RELEASED trước đó → không làm lại
        }
        Product p = productRepository.findById(r.getSku()).orElseThrow();
        p.setReserved(p.getReserved() - r.getQuantity()); // hàng rời kho: chỉ giảm reserved (available đã giảm lúc
                                                          // reserve)
        r.setStatus(ReservationStatus.COMMITTED);
    }

    @Transactional
    public void release(PaymentFailed payment) {
        Reservation r = reservationRepository.findById(payment.orderId())
                .orElseThrow(() -> new ReservationNotFoundException(payment.orderId().toString()));
        if (r.getStatus() != ReservationStatus.RESERVED) {
            return;
        }
        Product p = productRepository.findById(r.getSku()).orElseThrow();
        p.setReserved(p.getReserved() - r.getQuantity()); // trả hàng về: reserved → available
        p.setAvailable(p.getAvailable() + r.getQuantity());
        r.setStatus(ReservationStatus.RELEASED);
        outboxWriter.write("InventoryReleased", r.getOrderId().toString(),
                new InventoryReleased(r.getOrderId(), r.getSku(), r.getQuantity(), payment.customerName(),
                        payment.reason()));
    }

}
