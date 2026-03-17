--liquibase formatted sql

--changeset flightservice:1 labels:initial
CREATE TABLE IF NOT EXISTS airlines
(
    id   UUID        NOT NULL DEFAULT gen_random_uuid(),
    code VARCHAR(10) NOT NULL,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT pk_airlines PRIMARY KEY (id),
    CONSTRAINT uq_airlines_code UNIQUE (code)
);

--changeset flightservice:2 labels:initial
CREATE TABLE IF NOT EXISTS flights
(
    id                 UUID           NOT NULL DEFAULT gen_random_uuid(),
    flight_number      VARCHAR(10)    NOT NULL,
    airline_id         UUID           NOT NULL,
    origin_code        VARCHAR(3)     NOT NULL,
    destination_code   VARCHAR(3)     NOT NULL,
    departure_time     TIMESTAMPTZ    NOT NULL,
    arrival_time       TIMESTAMPTZ    NOT NULL,
    departure_date     DATE           NOT NULL,
    total_seats        INT            NOT NULL,
    available_seats    INT            NOT NULL,
    price              DECIMAL(10, 2) NOT NULL,
    status             VARCHAR(20)    NOT NULL DEFAULT 'SCHEDULED',
    CONSTRAINT pk_flights PRIMARY KEY (id),
    CONSTRAINT fk_flights_airline FOREIGN KEY (airline_id) REFERENCES airlines (id),
    CONSTRAINT uq_flight_number_date UNIQUE (flight_number, departure_date),
    CONSTRAINT chk_total_seats_positive CHECK (total_seats > 0),
    CONSTRAINT chk_available_seats_nonnegative CHECK (available_seats >= 0),
    CONSTRAINT chk_price_positive CHECK (price > 0),
    CONSTRAINT chk_available_le_total CHECK (available_seats <= total_seats),
    CONSTRAINT chk_flight_status CHECK (status IN ('SCHEDULED', 'DEPARTED', 'CANCELLED', 'COMPLETED'))
);

--changeset flightservice:3 labels:initial
CREATE INDEX IF NOT EXISTS idx_flights_route_date
    ON flights (origin_code, destination_code, departure_date);

CREATE INDEX IF NOT EXISTS idx_flights_status
    ON flights (status);

--changeset flightservice:4 labels:initial
CREATE TABLE IF NOT EXISTS seat_reservations
(
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    flight_id  UUID        NOT NULL,
    booking_id UUID        NOT NULL,
    seat_count INT         NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_seat_reservations PRIMARY KEY (id),
    CONSTRAINT fk_seat_reservations_flight FOREIGN KEY (flight_id) REFERENCES flights (id),
    CONSTRAINT uq_seat_reservations_booking UNIQUE (booking_id),
    CONSTRAINT chk_seat_count_positive CHECK (seat_count > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('ACTIVE', 'RELEASED', 'EXPIRED'))
);

--changeset flightservice:5 labels:initial
CREATE INDEX IF NOT EXISTS idx_seat_reservations_flight
    ON seat_reservations (flight_id);

--changeset flightservice:6 labels:seed-data
-- Insert sample airlines
INSERT INTO airlines (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'SU', 'Aeroflot'),
       ('00000000-0000-0000-0000-000000000002', 'S7', 'S7 Airlines'),
       ('00000000-0000-0000-0000-000000000003', 'DP', 'Pobeda')
ON CONFLICT DO NOTHING;

--changeset flightservice:7 labels:seed-data
-- Insert sample flights
INSERT INTO flights (id, flight_number, airline_id, origin_code, destination_code,
                     departure_time, arrival_time, departure_date,
                     total_seats, available_seats, price, status)
VALUES ('10000000-0000-0000-0000-000000000001',
        'SU1234',
        '00000000-0000-0000-0000-000000000001',
        'SVO', 'LED',
        '2026-04-01T10:00:00+03:00',
        '2026-04-01T11:30:00+03:00',
        '2026-04-01',
        180, 45, 5990.00, 'SCHEDULED'),
       ('10000000-0000-0000-0000-000000000002',
        'S71001',
        '00000000-0000-0000-0000-000000000002',
        'SVO', 'AER',
        '2026-04-01T08:00:00+03:00',
        '2026-04-01T10:45:00+03:00',
        '2026-04-01',
        160, 120, 8500.00, 'SCHEDULED'),
       ('10000000-0000-0000-0000-000000000003',
        'DP401',
        '00000000-0000-0000-0000-000000000003',
        'VKO', 'LED',
        '2026-04-01T14:00:00+03:00',
        '2026-04-01T15:20:00+03:00',
        '2026-04-01',
        189, 189, 2990.00, 'SCHEDULED')
ON CONFLICT DO NOTHING;
