package ai.happyrobot.carriersales.booking;

import ai.happyrobot.carriersales.booking.model.BookingRequest;
import ai.happyrobot.carriersales.booking.model.BookingResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private static final String CALL_ID = "X-Call-ID";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public BookingResult book(@RequestBody(required = false) BookingRequest request,
                              @RequestHeader(name = CALL_ID, required = false) String callId) {
        return request == null
                ? bookingService.book(callId, null, null)
                : bookingService.book(callId, request.mcNumber(), request.loadId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public BookingResult unreadableBody(HttpServletRequest request) {
        return bookingService.book(request.getHeader(CALL_ID), null, null);
    }
}
