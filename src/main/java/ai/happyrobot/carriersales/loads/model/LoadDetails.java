package ai.happyrobot.carriersales.loads.model;

// Everything the agent needs to pitch one load. Deliberately has no max_rate field.
// Always HTTP 200 with every field present; when available is false the load fields are empty ("" / 0).
public record LoadDetails(
        String error,
        String message,
        boolean available,
        String loadId,
        String origin,
        String destination,
        String pickupDatetime,
        String pickupDisplay,
        String deliveryDatetime,
        String deliveryDisplay,
        String equipmentType,
        int loadboardRate,
        int miles,
        int weight,
        String commodityType,
        int numOfPieces,
        String dimensions,
        String notes) {

    public static LoadDetails unavailable(String error, String message) {
        return new LoadDetails(error, message, false, "", "", "", "", "", "", "", "", 0, 0, 0, "", 0, "", "");
    }
}
