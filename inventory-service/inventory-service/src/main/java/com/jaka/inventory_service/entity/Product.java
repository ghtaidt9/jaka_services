package com.jaka.inventory_service.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @Column(length = 50)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    // Stock that can still be reserved; never negative (DB check, not just Java)
    @Column(nullable = false, check = @CheckConstraint(name = "products_available_check", constraint = "available >= 0"))
    private int available;

    // Stock held for orders that are not paid yet
    @Builder.Default
    @Column(nullable = false, check = @CheckConstraint(name = "products_reserved_check", constraint = "reserved >= 0"))
    private int reserved = 0;

    // Optimistic locking: two concurrent reservations on the same SKU → one fails instead of overselling
    @Version
    private Long version;
}
