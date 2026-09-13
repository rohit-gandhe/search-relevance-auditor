package labs.augmentor.auditor.audit;

import labs.augmentor.auditor.model.AuditRecord;
import java.util.List;

/** Where decisions are recorded. An interface so the worker is testable offline. */
public interface AuditLog {
    void append(AuditRecord record);

    List<AuditRecord> all();
}
