package ai.happyrobot.carriersales.tms.model;

import java.time.LocalDateTime;

public record TmsBooking(String loadId, String bookingRef, String status, LocalDateTime bookedAt) {
}
