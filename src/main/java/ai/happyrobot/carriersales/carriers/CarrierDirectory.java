package ai.happyrobot.carriersales.carriers;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Contact on file per MC (CARRIER_CONTACTS), the only place codes are sent. Production: the CRM.
@Component
public class CarrierDirectory {

    private final Map<String, String> emailsByMc = new HashMap<>();

    public CarrierDirectory(@Value("${app.carriers.contacts:}") String contacts) {
        for (String entry : contacts.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split(":", 2);
            // Don't echo the entry: it holds an email address.
            if (parts.length != 2 || !parts[0].trim().matches("\\d{1,8}") || !parts[1].trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new IllegalStateException("CARRIER_CONTACTS has an invalid entry; expected mc:email,mc:email");
            }
            emailsByMc.put(parts[0].trim(), parts[1].trim());
        }
    }

    public Optional<String> emailFor(String mcNumber) {
        return Optional.ofNullable(emailsByMc.get(mcNumber));
    }
}
