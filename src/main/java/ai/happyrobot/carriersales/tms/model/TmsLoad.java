package ai.happyrobot.carriersales.tms.model;

import java.time.LocalDateTime;

// Detail-only fields (deliveryAt..maxBuy) are null on LOAD_QUERY summaries.
public record TmsLoad(
        String loadId,
        String originCity, String originState, String originZip,
        String destinationCity, String destinationState, String destinationZip,
        LocalDateTime pickupAt,
        LocalDateTime deliveryAt,
        EquipmentType equipmentType,
        int rate,
        int miles,
        String status,
        Integer weightLbs,
        String commodity,
        Integer pieces,
        String dimensions,
        String notes,
        Integer maxBuy) {

    // MAX_BUY must never reach a log line, even at DEBUG via toString.
    @Override
    public String toString() {
        return "TmsLoad[loadId=" + loadId + ", " + originCity + "," + originState
                + " -> " + destinationCity + "," + destinationState + ", " + equipmentType
                + ", rate=" + rate + ", maxBuy=" + (maxBuy == null ? "n/a" : "***") + "]";
    }
}
