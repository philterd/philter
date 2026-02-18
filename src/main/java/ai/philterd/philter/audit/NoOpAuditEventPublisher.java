package ai.philterd.philter.audit;

import ai.philterd.philter.model.AuditLogEvent;
import org.bson.types.ObjectId;

import java.util.Map;

public class NoOpAuditEventPublisher implements AuditEventPublisher {

    @Override
    public void publishAuditEvent(Map<String, Object> auditEvent) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, String clientIpAddress) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject, String clientIpAddress) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject, String clientIpAddress, String details) {

    }

}
