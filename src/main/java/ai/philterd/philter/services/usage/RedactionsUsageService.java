package ai.philterd.philter.services.usage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

public interface RedactionsUsageService {

    Map<String, Long> getTokensPreviousXDays(int previousDays) throws IOException;

    Map<String, Long> getTokensPreviousCalendarMonth() throws IOException;

    Map<String, Long> getTokens(LocalDate startDate, LocalDate endDate) throws IOException;

    Map<String, Long> getRedactionsPreviousXDays(int previousDays) throws IOException;

    Map<String, Long> getRedactionsPreviousCalendarMonth() throws IOException;

    Map<String, Long> getRedactions(LocalDate startDate, LocalDate endDate) throws IOException;

}
