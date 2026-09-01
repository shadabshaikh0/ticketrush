-- Seats for a show. `version` powers the OPTIMISTIC strategy.
CREATE TABLE IF NOT EXISTS seat (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id   BIGINT       NOT NULL,
    label     VARCHAR(16)  NOT NULL,
    status    VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | BOOKED
    version   BIGINT       NOT NULL DEFAULT 0,
    booked_by VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_seat_show ON seat(show_id);

-- Bookings. NOTE: intentionally NO unique constraint on seat_id.
-- A DB unique index would itself prevent double-booking — but the whole point of
-- this project is to prove correctness in the APPLICATION's concurrency logic,
-- so we deliberately let the naive strategy insert duplicates and measure it.
CREATE TABLE IF NOT EXISTS booking (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seat_id         BIGINT       NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(80),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_booking_seat ON booking(seat_id);

-- M2: a hold is a lease — the seat is reserved until hold_expires_at, then released.
ALTER TABLE seat ADD COLUMN IF NOT EXISTS hold_expires_at TIMESTAMPTZ;

-- M2: idempotency — a given key can create at most one booking. NULLs are distinct in
-- a Postgres unique index, so the naive stampede (which sends no key) can still
-- double-book exactly as the M0/M1 demo intends.
CREATE UNIQUE INDEX IF NOT EXISTS uq_booking_idem ON booking(idempotency_key);
