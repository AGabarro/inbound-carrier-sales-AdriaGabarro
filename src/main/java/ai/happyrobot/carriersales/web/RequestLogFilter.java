package ai.happyrobot.carriersales.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// One line per API request with X-Call-ID. Runs before ApiKeyFilter; never logs headers or bodies.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    static final String CALL_ID_HEADER = "X-Call-ID";
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String query = request.getQueryString() == null ? "" : "?" + safe(request.getQueryString());
            log.info("API {} {}{} call={} -> {} in {}ms",
                    request.getMethod(), request.getRequestURI(), query,
                    safe(request.getHeader(CALL_ID_HEADER)), response.getStatus(),
                    (System.nanoTime() - start) / 1_000_000);
        }
    }

    // Caller values: one line, bounded, so they can't forge log lines.
    private static String safe(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]", "_");
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }
}
