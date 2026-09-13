package labs.augmentor.auditor.audit;

import labs.augmentor.auditor.model.AuditRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemoryAuditLog implements AuditLog {

    private final List<AuditRecord> records = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void append(AuditRecord record) {
        records.add(record);
    }

    @Override
    public List<AuditRecord> all() {
        return List.copyOf(records);
    }
}
