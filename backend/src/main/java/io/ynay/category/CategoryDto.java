package io.ynay.category;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** {@code Category}. {@code systemKind} is READY_TO_ASSIGN, CREDIT_CARD_PAYMENT or null. */
public record CategoryDto(
        UUID id,
        UUID groupId,
        String name,
        boolean hidden,
        String note,
        int sortOrder,
        SystemKind systemKind,
        UUID linkedAccountId,
        TargetDto target) {

    public enum SystemKind { READY_TO_ASSIGN, CREDIT_CARD_PAYMENT }

    @JsonIgnore
    public boolean isSystem() {
        return systemKind != null;
    }

    @JsonIgnore
    public boolean isReadyToAssign() {
        return systemKind == SystemKind.READY_TO_ASSIGN;
    }
}
