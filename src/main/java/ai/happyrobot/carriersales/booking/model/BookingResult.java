package ai.happyrobot.carriersales.booking.model;

// transferred: the (mocked) handoff to a senior rep happened.
public record BookingResult(
        String error,
        String message,
        boolean booked,
        String loadId,
        int agreedRate,
        String bookingReference,
        boolean transferred,
        String repQueue) {

    public static BookingResult problem(String error, String message) {
        return new BookingResult(error, message, false, "", 0, "", false, "");
    }
}
