package ai.philterd.philter.services.usage.apirequests;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

public interface ApiRequestsUsageService {

    Map<String, Long> getApiRequests(final LocalDate startDate, final LocalDate endDate) throws IOException;

    Map<String, Long> getApiRequestsPreviousCalendarMonth() throws IOException;

    Map<String, Long> getApiRequestsLastXDays(final int previousDays) throws IOException;

    long getCountOfApiRequestsInPreviousCalendarMonth() throws IOException;

}
