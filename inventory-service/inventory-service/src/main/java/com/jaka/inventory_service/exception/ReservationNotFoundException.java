package com.jaka.inventory_service.exception;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(String orderId) {
        super("Reservation not found for orderId: " + orderId);
    }
}