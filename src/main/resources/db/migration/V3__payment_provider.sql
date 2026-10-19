-- The provider chosen at checkout; Order passes it to Payment in PAYMENT_REQUESTED once stock is held.
ALTER TABLE orders ADD (payment_provider VARCHAR2(20));
