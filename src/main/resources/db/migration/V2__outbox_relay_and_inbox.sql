-- Outbox relay bookkeeping: how often a row failed to publish and why. ORDER_CREATED lists every line, so widen the payload.
ALTER TABLE order_outbox ADD (
    attempts   NUMBER(10)      DEFAULT 0 NOT NULL,
    last_error VARCHAR2(1000)
);
ALTER TABLE order_outbox MODIFY (payload VARCHAR2(32767));

-- Consumer-side dedupe (TDD §13.6): an event id seen here has already been applied.
CREATE TABLE inbox_events (
    event_id    RAW(16)      NOT NULL,
    consumer    VARCHAR2(60) NOT NULL,
    received_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_inbox_events PRIMARY KEY (event_id)
);
