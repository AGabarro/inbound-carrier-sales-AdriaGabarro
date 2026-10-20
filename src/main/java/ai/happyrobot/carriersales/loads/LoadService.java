package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.loads.model.LoadDetails;
import ai.happyrobot.carriersales.loads.model.LoadLookup;
import ai.happyrobot.carriersales.loads.model.LoadSummary;
import ai.happyrobot.carriersales.loads.model.SearchResult;
import ai.happyrobot.carriersales.tms.TmsClient;
import ai.happyrobot.carriersales.tms.exception.TmsErrorException;
import ai.happyrobot.carriersales.tms.exception.TmsFaultException;
import ai.happyrobot.carriersales.tms.model.TmsLoad;
import ai.happyrobot.carriersales.tms.protocol.TmsRequest;
import ai.happyrobot.carriersales.web.exception.BadRequestException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Spoken filters -> TMS query -> only bookable loads, as small speakable results. MAX_BUY never leaves.
@Service
public class LoadService {

    private static final Logger log = LoggerFactory.getLogger(LoadService.class);

    private static final int MAX_RESULTS = 3;
    // Each search candidate is confirmed with its full record; this bounds the extra TMS calls.
    private static final int MAX_DETAIL_LOOKUPS = 5;
    // A truck averages well under this over a whole trip; above it the load data can't be real.
    private static final double MAX_PLAUSIBLE_MPH = 60;
    private static final Pattern LOAD_ID = Pattern.compile("[A-Z0-9]{1,12}");

    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String NOT_AVAILABLE = "NOT_AVAILABLE";
    static final String TMS_UNAVAILABLE = "TMS_UNAVAILABLE";
    static final String TMS_ERROR = "TMS_ERROR";

    private static final String UNKNOWN_LOAD = "I couldn't find a load with that number.";
    private static final String NO_LONGER_AVAILABLE = "That load is no longer available.";
    private static final String SYSTEM_SLOW = "Our load system is slow right now, give me a moment and I'll try again.";
    private static final String SYSTEM_ERROR = "Our load system couldn't handle that request right now.";

    private final TmsClient tms;
    private final Clock clock;

    @Autowired
    public LoadService(TmsClient tms) {
        this(tms, Clock.system(LoadFormatter.BROKERAGE_TIME));
    }

    LoadService(TmsClient tms, Clock clock) {
        this.tms = tms;
        this.clock = clock;
    }

    public SearchResult search(String originState, String originCity, String destinationState,
                               String destinationCity, String equipment, String pickupDate) {
        try {
            // Equipment we can't filter on: search the whole lane and say so.
            boolean unknownEquipment = hasText(equipment) && LoadFormatter.equipment(equipment).isEmpty();
            Map<String, String> filters = filters(originState, originCity, destinationState, destinationCity,
                    equipment, pickupDate);
            if (filters.isEmpty()) {
                throw new BadRequestException(unknownEquipment
                        ? "I don't have " + spokenEquipment(equipment) + " loads listed. Which lane are you running?"
                        : "Where would you like to pick up or deliver, and what equipment do you run?");
            }
            LocalDateTime now = LocalDateTime.now(clock);
            List<TmsLoad> candidates = tms.send(TmsRequest.loadQuery(filters)).stream()
                    .filter(load -> isOffered(load, now))
                    .sorted(Comparator.comparing(TmsLoad::pickupAt))
                    .limit(MAX_DETAIL_LOOKUPS)
                    .toList();
            List<LoadSummary> loads = bookable(candidates, now);

            if (loads.isEmpty()) {
                return new SearchResult("", "Nothing open on that lane right now.", false, List.of());
            }
            String found = loads.size() == 1 ? "I've got 1 load" : "I've got " + loads.size() + " loads";
            String message = unknownEquipment
                    ? "I don't have " + spokenEquipment(equipment) + " loads listed, but here's what's moving on that "
                            + "lane: " + found + ". Each one says the equipment it needs."
                    : found + " for you.";
            return new SearchResult("", message, true, loads);
        } catch (BadRequestException e) {
            return SearchResult.problem(INVALID_REQUEST, e.getMessage());
        } catch (TmsFaultException e) {
            return SearchResult.problem(TMS_UNAVAILABLE, SYSTEM_SLOW);
        } catch (TmsErrorException e) {
            if (e.code().equals("MISSING_FIELD")) {
                return SearchResult.problem(INVALID_REQUEST,
                        "I couldn't search with those details. Can you give me the lane again?");
            }
            log.warn("Unexpected TMS error {}: {}", e.code(), e.tmsMessage());
            return SearchResult.problem(TMS_ERROR, SYSTEM_ERROR);
        }
    }

    public LoadDetails details(String loadId) {
        LoadLookup lookup = findBookable(loadId);
        return lookup.load() == null
                ? LoadDetails.unavailable(lookup.error(), lookup.message())
                : toDetails(lookup.load());
    }

    // Includes MAX_BUY: callers must never put it in a response.
    public LoadLookup findBookable(String loadId) {
        if (loadId == null || loadId.isBlank()) {
            return LoadLookup.problem(INVALID_REQUEST, "Which load number are you calling about?");
        }
        // Spoken IDs arrive as "ld 00994", "LD-00994"...: keep letters and digits only.
        String id = loadId.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-_.#]", "");
        if (!LOAD_ID.matcher(id).matches()) {
            return LoadLookup.problem(INVALID_REQUEST, "I didn't catch the load number. Can you read it again?");
        }

        try {
            List<TmsLoad> found = tms.send(TmsRequest.loadGet(id));
            if (found.isEmpty()) {
                return LoadLookup.problem(NOT_FOUND, UNKNOWN_LOAD);
            }
            TmsLoad load = found.getFirst();
            // Exists but can't be offered: covered, pickup passed, or implausible data.
            if (!isBookable(load, LocalDateTime.now(clock))) {
                return LoadLookup.problem(NOT_AVAILABLE, NO_LONGER_AVAILABLE);
            }
            return LoadLookup.found(load);
        } catch (TmsFaultException e) {
            return LoadLookup.problem(TMS_UNAVAILABLE, SYSTEM_SLOW);
        } catch (TmsErrorException e) {
            if (e.code().equals("NOT_FOUND")) {
                return LoadLookup.problem(NOT_FOUND, UNKNOWN_LOAD);
            }
            log.warn("Unexpected TMS error {}: {}", e.code(), e.tmsMessage());
            return LoadLookup.problem(TMS_ERROR, SYSTEM_ERROR);
        }
    }

    // HappyRobot sends every param, empty when unknown.
    private static Map<String, String> filters(String originState, String originCity, String destinationState,
                                               String destinationCity, String equipment, String pickupDate) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (hasText(originState)) filters.put("ORIG_STATE", LoadFormatter.state(originState));
        if (hasText(originCity)) filters.put("ORIG_CITY", LoadFormatter.city(originCity));
        if (hasText(destinationState)) filters.put("DEST_STATE", LoadFormatter.state(destinationState));
        if (hasText(destinationCity)) filters.put("DEST_CITY", LoadFormatter.city(destinationCity));
        if (hasText(equipment)) {
            LoadFormatter.equipment(equipment).ifPresent(type -> filters.put("EQTYPE", type.name()));
        }
        if (hasText(pickupDate)) filters.put("PICKUP_DATE", LoadFormatter.tmsDate(pickupDate));
        return filters;
    }

    // Echoed back to the carrier, so only plain words.
    private static String spokenEquipment(String spoken) {
        String words = spoken.trim().toLowerCase(Locale.ROOT);
        return words.matches("[a-z][a-z ]{0,20}") ? words : "that equipment";
    }

    // Summaries have no delivery time: confirm each candidate with its full record so search and details agree.
    private List<LoadSummary> bookable(List<TmsLoad> candidates, LocalDateTime now) {
        List<LoadSummary> bookable = new ArrayList<>();
        for (TmsLoad candidate : candidates) {
            if (bookable.size() == MAX_RESULTS) {
                break;
            }
            try {
                List<TmsLoad> full = tms.send(TmsRequest.loadGet(candidate.loadId()));
                if (!full.isEmpty() && isBookable(full.getFirst(), now)) {
                    bookable.add(toSummary(full.getFirst()));
                }
            } catch (TmsFaultException | TmsErrorException e) {
                // Can't confirm it right now: skip it.
                log.info("Load {} skipped in search: {}", candidate.loadId(), e.getMessage());
            }
        }
        return bookable;
    }

    private static boolean isBookable(TmsLoad load, LocalDateTime now) {
        return isOffered(load, now) && !isImplausible(load);
    }

    private static boolean isOffered(TmsLoad load, LocalDateTime now) {
        return "OPEN".equals(load.status()) && load.pickupAt().isAfter(now);
    }

    private static boolean isImplausible(TmsLoad load) {
        if (!load.deliveryAt().isAfter(load.pickupAt())) {
            log.info("Load {} hidden: delivery is not after pickup", load.loadId());
            return true;
        }
        double hours = Duration.between(load.pickupAt(), load.deliveryAt()).toMinutes() / 60.0;
        double mph = load.miles() / hours;
        if (mph > MAX_PLAUSIBLE_MPH) {
            log.info("Load {} hidden: implausible average speed of {} mph", load.loadId(), Math.round(mph));
            return true;
        }
        return false;
    }

    private static LoadSummary toSummary(TmsLoad load) {
        return new LoadSummary(
                load.loadId(),
                load.originCity() + ", " + load.originState(),
                load.destinationCity() + ", " + load.destinationState(),
                LoadFormatter.iso(load.pickupAt()),
                LoadFormatter.display(load.pickupAt()),
                LoadFormatter.equipmentLabel(load.equipmentType()),
                load.rate(),
                load.miles());
    }

    private static LoadDetails toDetails(TmsLoad load) {
        return new LoadDetails(
                "",
                "",
                true,
                load.loadId(),
                load.originCity() + ", " + load.originState(),
                load.destinationCity() + ", " + load.destinationState(),
                LoadFormatter.iso(load.pickupAt()),
                LoadFormatter.display(load.pickupAt()),
                LoadFormatter.iso(load.deliveryAt()),
                LoadFormatter.display(load.deliveryAt()),
                LoadFormatter.equipmentLabel(load.equipmentType()),
                load.rate(),
                load.miles(),
                load.weightLbs(),
                load.commodity(),
                load.pieces(),
                load.dimensions(),
                load.notes());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
