package ai.happyrobot.carriersales.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import ai.happyrobot.carriersales.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every /api/** request must carry the shared key in X-API-Key. /health stays open (liveness only, no data). */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid API key.\"}";

    private final byte[] expectedKey;

    public ApiKeyFilter(SecurityProperties properties) {
        this.expectedKey = properties.apiKey().getBytes(UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        // Constant-time comparison: response timing doesn't reveal how much of the key was right.
        if (key != null && MessageDigest.isEqual(key.getBytes(UTF_8), expectedKey)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_BODY);
    }
}
