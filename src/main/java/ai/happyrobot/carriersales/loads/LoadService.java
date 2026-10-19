package ai.happyrobot.carriersales.loads;

import ai.happyrobot.carriersales.loads.model.LoadDetails;
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
import java.time.ZoneId;
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

/**
 * Load search and detail for the voice agent: turns spoken filters into a TMS query, hides loads that
 * can't be offered, and maps what's left to small, speakable responses. MAX_BUY never leaves this class.
 * Every outcome, including bad input and a TMS outage, is a normal result with an error code and a
 * sentence: HappyRobot only reads 200 responses.
 */
@Service
public class LoadService {

    private static final Logger log = LoggerFactory.getLogger(LoadService.class);

    // TMS dates carry no timezone; we read them as the brokerage's local time (Chicago).
    static final ZoneId BROKERAGE_TIME = ZoneId.of("America/Chicago");
    private static final int MAX_RESULTS = 3;
    // A truck averages well under this over a whole trip; above it the load data can't be real.
    private static final double MAX_PLAUSIBLE_MPH = 60;
    private static final Pattern LOAD_ID = Pattern.compile("[A-Z0-9]{1,12}");

    static final String INVALID_REQUEST = "INVALID_REQUEST";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String TMS_UNAVAILABLE = "TMS_UNAVAILABLE";
    static final String TMS_ERROR = "TMS_ERROR";

    private static final String NOT_AVAILABLE = "That load isn't available.";
    private static final String SYSTEM_SLOW = "Our load system is slow right now, give me a moment and I'll try again.";
    private static final String SYSTEM_ERROR = "Our load system couldn't handle that request right now.";

    private final TmsClient tms;
    private final Clock clock;

    @Autowired
    public LoadService(TmsClient tms) {
        this(tms, Clock.system(BROKERAGE_TIME));
    }

    // Tests pass a fixed clock so "pickup already passed" is deterministic.
    LoadService(TmsClient tms, Clock clock) {
        this.tms = tms;
        this.clock = clock;
    }

    public SearchResult search(String originState, String originCity, String destinationState,
                               String destinationCity, String equipment, String pickupDate) {
        try {
            Map<String, String> filters = filters(originState, originCity, destinationState, destinationCity,
                    equipment, pickupDate);
            LocalDateTime now = LocalDateTime.now(clock);
            List<LoadSummary> loads = tms.send(TmsRequest.loadQuery(filters)).stream()
                    .filter(load -> isOffered(load, now))
                    .sorted(Comparator.comparing(TmsLoad::pickupAt))
                    .limit(MAX_RESULTS)
                    .map(LoadService::toSummary)
                    .toList();

            if (loads.isEmpty()) {
                return new SearchResult("", "No loads found for that lane.", false, List.of());
            }
            String message = loads.size() == 1 ? "I found 1 load." : "I found " + loads.size() + " loads.";
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
        if (loadId == null || loadId.isBlank()) {
            return LoadDetails.unavailable(INVALID_REQUEST, "Which load number are you calling about?");
        }
        String id = loadId.trim().toUpperCase(Locale.ROOT);
        if (!LOAD_ID.matcher(id).matches()) {
            return LoadDetails.unavailable(INVALID_REQUEST, "I didn't catch the load number. Can you read it again?");
        }

        try {
            List<TmsLoad> found = tms.send(TmsRequest.loadGet(id));
            if (found.isEmpty()) {
                return LoadDetails.unavailable(NOT_FOUND, NOT_AVAILABLE);
            }
            TmsLoad load = found.getFirst();
            if (!isOffered(load, LocalDateTime.now(clock)) || isImplausible(load)) {
                return LoadDetails.unavailable(NOT_FOUND, NOT_AVAILABLE);
            }
            return toDetails(load);
        } catch (TmsFaultException e) {
            return LoadDetails.unavailable(TMS_UNAVAILABLE, SYSTEM_SLOW);
        } catch (TmsErrorException e) {
            if (e.code().equals("NOT_FOUND")) {
                return LoadDetails.unavailable(NOT_FOUND, NOT_AVAILABLE);
            }
            log.warn("Unexpected TMS error {}: {}", e.code(), e.tmsMessage());
            return LoadDetails.unavailable(TMS_ERROR, SYSTEM_ERROR);
        }
    }

    // Blank values are ignored: HappyRobot always sends all six params, empty when not mentioned.
    private static Map<String, String> filters(String originState, String originCity, String destinationState,
                                               String destinationCity, String equipment, String pickupDate) {
        Map<String, String> filters = new LinkedHashMap<>();
        if (hasText(originState)) filters.put("ORIG_STATE", LoadFormatter.state(originState));
        if (hasText(originCity)) filters.put("ORIG_CITY", LoadFormatter.city(originCity));
        if (hasText(destinationState)) filters.put("DEST_STATE", LoadFormatter.state(destinationState));
        if (hasText(destinationCity)) filters.put("DEST_CITY", LoadFormatter.city(destinationCity));
        if (hasText(equipment)) filters.put("EQTYPE", LoadFormatter.equipment(equipment).name());
        if (hasText(pickupDate)) filters.put("PICKUP_DATE", LoadFormatter.tmsDate(pickupDate));
        if (filters.isEmpty()) {
            throw new BadRequestException("Where would you like to pick up or deliver, and what equipment do you run?");
        }
        return filters;
    }

    private static boolean isOffered(TmsLoad load, LocalDateTime now) {
        return "OPEN".equals(load.status()) && load.pickupAt().isAfter(now);
    }

    // e.g. LD00992: Honolulu -> Amarillo, 3,495 miles in 21 hours = 166 mph.
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
                LoadFormatter.spoken(load.pickupAt()),
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
                LoadFormatter.spoken(load.pickupAt()),
                LoadFormatter.spoken(load.deliveryAt()),
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
