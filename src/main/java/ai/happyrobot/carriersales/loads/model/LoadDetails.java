package ai.happyrobot.carriersales.loads.model;

// Everything the agent needs to pitch one load. Deliberately has no max_rate field.
// When available is false, only message is set and every other field is left out of the JSON.
public record LoadDetails(
        boolean available,
        String message,
        String loadId,
        String origin,
        String destination,
        String pickupDatetime,
        String deliveryDatetime,
        String equipmentType,
        Integer loadboardRate,
        Integer miles,
        Integer weight,
        String commodityType,
        Integer numOfPieces,
        String dimensions,
        String notes) {

    public static LoadDetails unavailable(String message) {
        return new LoadDetails(false, message, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
