package com.learning.order_service.core.domain;

/** Postal address snapshot, stored on the order so later profile edits never change a placed order. */
public record Address(String name, String line1, String line2, String city, String state, String postalCode,
                      String country, String phone) {
}
