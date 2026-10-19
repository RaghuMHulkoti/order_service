-- Each payment attempt opens its own intent; the order counts them so a customer can retry after a decline.
ALTER TABLE orders ADD (payment_attempts NUMBER(10) DEFAULT 1 NOT NULL);
