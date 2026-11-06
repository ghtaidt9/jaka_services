package com.jaka.inventory_service.controller;

import com.jaka.inventory_service.entity.Product;
import com.jaka.inventory_service.entity.Reservation;
import com.jaka.inventory_service.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/products")
    public List<Product> getAllProducts() {
        return inventoryService.getAllProducts();
    }

    @GetMapping("/products/{sku}")
    public ResponseEntity<Product> getProduct(@PathVariable String sku) {
        return ResponseEntity.of(inventoryService.getProduct(sku));
    }

    @GetMapping("/reservations")
    public List<Reservation> getAllReservations() {
        return inventoryService.getAllReservations();
    }
}
