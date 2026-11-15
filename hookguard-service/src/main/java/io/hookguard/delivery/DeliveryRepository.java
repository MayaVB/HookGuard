package io.hookguard.delivery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.hookguard.delivery.api.DeliverySummary;
import io.hookguard.delivery.api.StatusCount;

/** Read side of deliveries. Writes after creation go through DeliveryStateStore. */
public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    @Query(value = """
            select new io.hookguard.delivery.api.DeliverySummary(
                d.id, ev.id, ev.eventType, en.id, en.url, d.status, d.attemptCount,
                d.lastHttpStatus, d.lastError, d.claimedBy, d.claimExpiresAt,
                case when d.claimExpiresAt < current_timestamp then true else false end,
                d.createdAt, d.updatedAt, d.completedAt)
            from Delivery d join d.event ev join d.endpoint en
            where (:status is null or d.status = :status)
            order by d.createdAt desc, d.id
            """,
            countQuery = "select count(d) from Delivery d where (:status is null or d.status = :status)")
    Page<DeliverySummary> findSummaries(@Param("status") DeliveryStatus status, Pageable pageable);

    @Query("select d from Delivery d join fetch d.event join fetch d.endpoint where d.id = :id")
    Optional<Delivery> findWithEventAndEndpoint(@Param("id") UUID id);

    @Query("select new io.hookguard.delivery.api.StatusCount(d.status, count(d)) from Delivery d group by d.status")
    List<StatusCount> countByStatus();

    /** Evaluated with the database clock, the same clock that wrote claim_expires_at. */
    @Query("select case when d.claimExpiresAt < current_timestamp then true else false end from Delivery d where d.id = :id")
    boolean isLeaseExpired(@Param("id") UUID id);
}
