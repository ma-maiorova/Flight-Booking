package com.flightbooking.bookingservice.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
@NoArgsConstructor
public class Booking implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private transient boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Cross-service reference — no DB FK constraint
    @Column(name = "flight_id", nullable = false)
    private UUID flightId;

    @Column(name = "passenger_name", nullable = false, length = 200)
    private String passengerName;

    @Column(name = "passenger_email", nullable = false, length = 200)
    private String passengerEmail;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    // Price snapshot at booking time
    @Column(name = "total_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.CONFIRMED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PostLoad
    @PostPersist
    private void markNotNew() {
        this.isNew = false;
    }
}
