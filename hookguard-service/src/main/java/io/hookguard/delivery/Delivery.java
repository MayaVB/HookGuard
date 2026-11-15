package io.hookguard.delivery;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import io.hookguard.endpoint.Endpoint;
import io.hookguard.event.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Durable state of delivering one event to one endpoint.
 *
 * <p>JPA only inserts and reads this entity ({@link Immutable}). Every state transition is
 * an explicit compare-and-set UPDATE in {@code DeliveryStateStore}, so concurrent workers
 * cannot overwrite each other through JPA dirty checking.
 */
@Entity
@Table(name = "deliveries")
@Immutable
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private Endpoint endpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // Database-generated (DEFAULT now()): scheduling uses only the database clock.
    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private Instant nextAttemptAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_by", length = 200)
    private String claimedBy;

    @Column(name = "claim_expires_at")
    private Instant claimExpiresAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    protected Delivery() {
        // for JPA
    }

    public static Delivery pendingFor(Event event, Endpoint endpoint) {
        Delivery delivery = new Delivery();
        delivery.event = event;
        delivery.endpoint = endpoint;
        delivery.status = DeliveryStatus.PENDING;
        delivery.attemptCount = 0;
        return delivery;
    }

    public UUID getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
