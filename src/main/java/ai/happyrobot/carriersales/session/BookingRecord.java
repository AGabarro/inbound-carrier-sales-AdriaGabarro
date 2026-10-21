package ai.happyrobot.carriersales.session;

import java.time.Instant;

// status: "booked" or "unconfirmed" (a rep confirms).
public record BookingRecord(
        String loadId,
        String mcNumber,
        int agreedRate,
        String reference,
        String status,
        boolean transferred,
        String repQueue,
        Instant at) {
}
