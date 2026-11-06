package com.jaka.inventory_service.exception;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(String sku) {
        super("SKU not found: " + sku);
    }
    
}
