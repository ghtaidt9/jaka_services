package com.jaka.order_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.jaka.order_service.entity.OrderSummaryResponse;
import com.jaka.order_service.repository.OrderSummaryRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class OrderSummaryController {
    private final OrderSummaryRepository summaries;

    @GetMapping("/{id}/summary") // context-path /api/orders → /api/orders/{id}/summary
    public ResponseEntity<OrderSummaryResponse> get(@PathVariable Long id) {
        return summaries.findById(id)
                .map(OrderSummaryResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
