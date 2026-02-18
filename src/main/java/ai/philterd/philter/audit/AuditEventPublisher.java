package ai.philterd.philter.audit;

import ai.philterd.philter.model.AuditLogEvent;
import org.bson.types.ObjectId;

import java.util.Map;

public interface AuditEventPublisher {


    void publishAuditEvent(final Map<String, Object> auditEvent);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final String clientIpAddress);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress, final String details);

}
