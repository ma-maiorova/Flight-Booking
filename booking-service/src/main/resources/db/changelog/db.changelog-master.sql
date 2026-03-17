--liquibase formatted sql

--changeset bookingservice:1 labels:initial
CREATE TABLE IF NOT EXISTS bookings
(
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID           NOT NULL,
    flight_id       UUID           NOT NULL,
    passenger_name  VARCHAR(200)   NOT NULL,
    passenger_email VARCHAR(200)   NOT NULL,
    seat_count      INT            NOT NULL,
    total_price     DECIMAL(10, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'CONFIRMED',
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_bookings PRIMARY KEY (id),
    CONSTRAINT chk_seat_count_positive CHECK (seat_count > 0),
    CONSTRAINT chk_total_price_positive CHECK (total_price > 0),
    CONSTRAINT chk_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

--changeset bookingservice:2 labels:initial
CREATE INDEX IF NOT EXISTS idx_bookings_user_id ON bookings (user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_flight_id ON bookings (flight_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status ON bookings (status);
