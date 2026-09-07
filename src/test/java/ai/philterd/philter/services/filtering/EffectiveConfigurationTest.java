package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.data.entities.*;
import ai.philterd.philter.data.services.*;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EffectiveConfigurationTest {
    @Test void snapshotFreezesCustomListsFpeGlobalListsAndContextFlags() throws Exception {
        var users = mock(UserService.class); var lists = mock(CustomListDataService.class);
        var contexts = mock(ContextDataService.class); var globalLists = mock(RedactListsDataService.class);
        var service = new RedactionService(mock(MongoClient.class, RETURNS_DEEP_STUBS), mock(PolicyDataService.class), lists, globalLists,
                contexts, mock(AuditEventPublisher.class), mock(LedgerDataService.class), users,
                new SimpleMeterRegistry(), mock(PhieldPublisher.class), mock(PiiCountAggregatePublisher.class), new RedactionCache());
        var user = new UserEntity(); user.setId(new ObjectId());
        when(users.findOneById(user.getId())).thenReturn(user);
        when(users.ensureFpeKey(user)).thenReturn("0123456789abcdef0123456789abcdef");
        when(lists.findItemsByNames(eq(user.getId()), any())).thenReturn(java.util.Map.of("names", java.util.List.of("captured-name")));
        var redact = new RedactListsEntity(); redact.setTermsToAlwaysRedact(java.util.List.of("captured-always"));
        redact.setTermsToNeverRedact(java.util.List.of("captured-never")); when(globalLists.find(user.getId())).thenReturn(redact);
        var context = new ContextEntity(); context.setContextName("context"); context.setLedger(true); context.setDisambiguation(false);
        when(contexts.findOneByNameAndUserId("context", user.getId())).thenReturn(context);
        var policy = new PolicyEntity(); policy.setName("p"); policy.setUserId(user.getId());
        policy.setPolicy("{\"identifiers\":{\"dictionaries\":[{\"terms\":[\"list:names\"]}]}}");
        final String captured = service.captureEffectiveConfiguration(policy, "context");
        var config = new Gson().fromJson(captured, EffectiveConfiguration.class);
        var resolved = new Gson().fromJson(config.policyJson(), Policy.class);
        assertEquals("0123456789abcdef0123456789abcdef", resolved.getFpe().getKey());
        assertTrue(config.policyJson().contains("captured-name"));
        assertTrue(config.policyJson().contains("captured-always"));
        assertTrue(config.policyJson().contains("captured-never"));
        assertTrue(org.bson.Document.parse(config.contextJson()).getBoolean("ledger"));
        // Dependency stores and account defaults become unavailable after submission.
        reset(lists, globalLists, contexts); when(users.ensureFpeKey(user)).thenThrow(new AssertionError("Must use captured key"));
        var filter = mock(ai.philterd.phileas.services.filters.filtering.PlainTextFilterService.class);
        var field = RedactionService.class.getDeclaredField("plainTextFilterServices"); field.setAccessible(true);
        ((java.util.Map<Boolean, Object>) field.get(service)).put(false, filter);
        var pin = new PinnedPolicy("p", 0, PolicyVersionDataService.contentHash(policy.getPolicy()), policy.getPolicy(), captured,
                PolicyVersionDataService.contentHash(captured));
        assertThrows(IllegalStateException.class, () -> service.filter("p", user.getId(), "context", "text".getBytes(),
                ai.philterd.phileas.model.filtering.MimeType.TEXT_PLAIN, pin, null, "document", () -> { throw new IllegalStateException("stop before publication"); }));
        var actual = org.mockito.ArgumentCaptor.forClass(Policy.class);
        verify(filter).filter(actual.capture(), any(), any(), eq("context"), eq("text"));
        assertTrue(new Gson().toJson(actual.getValue()).contains("captured-name"));
        verifyNoInteractions(lists, globalLists, contexts);
    }
}
