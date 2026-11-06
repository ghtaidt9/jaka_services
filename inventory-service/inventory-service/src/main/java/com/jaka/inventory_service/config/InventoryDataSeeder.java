package com.jaka.inventory_service.config;

import com.jaka.inventory_service.entity.Product;
import com.jaka.inventory_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class InventoryDataSeeder implements CommandLineRunner {

    private final ProductRepository productRepository;

    // Reservations start empty: they are created by OrderCreated events, not seeded
    @Override
    public void run(String... args) {
        if (productRepository.count() > 0) {
            return;
        }

        productRepository.saveAll(List.of(
                Product.builder().sku("TEST-001").name("Test product").available(100).build(),
                Product.builder().sku("TSHIRT-RED-M").name("T-shirt red M").available(50).build(),
                Product.builder().sku("TSHIRT-RED-L").name("T-shirt red L").available(30).build(),
                Product.builder().sku("MUG-WHITE").name("White mug").available(5).build(),
                Product.builder().sku("SOLD-OUT-001").name("Sold-out product").available(0).build()
        ));
    }
}
