package io.ynay.payee;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** {@code Payee}. Transfer payees ("Transfer : Savings") carry the account they transfer to. */
public record PayeeDto(UUID id, String name, UUID transferAccountId) {

    @JsonIgnore
    public boolean isTransfer() {
        return transferAccountId != null;
    }
}
