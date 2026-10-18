package com.learning.order_service.web.request;

import com.learning.order_service.core.domain.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String state,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Size(max = 100) String country,
        @Size(max = 30) String phone
) {

    public Address toDomain() {
        return new Address(name, line1, line2, city, state, postalCode, country, phone);
    }
}
