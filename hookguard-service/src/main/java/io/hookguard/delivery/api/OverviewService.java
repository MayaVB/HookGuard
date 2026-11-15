package io.hookguard.delivery.api;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.hookguard.messaging.RabbitTopology;

/** Aggregates database, broker and worker state into one snapshot (read-only, aggregate SQL). */
@Service
public class OverviewService {

    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final DeliveryQueryService deliveryQueries;
    private final JdbcClient jdbc;
    private final AmqpAdmin amqpAdmin;

    public OverviewService(DeliveryQueryService deliveryQueries, JdbcClient jdbc, AmqpAdmin amqpAdmin) {
        this.deliveryQueries = deliveryQueries;
        this.jdbc = jdbc;
        this.amqpAdmin = amqpAdmin;
    }

    @Transactional(readOnly = true)
    public OverviewResponse snapshot() {
        OffsetDateTime now = jdbc.sql("SELECT now()").query(OffsetDateTime.class).single();
        long attempts = jdbc.sql("SELECT count(*) FROM delivery_attempts").query(Long.class).single();
        return new OverviewResponse(
                now.toInstant(),
                deliveryQueries.stats(),
                attempts,
                queueStats(RabbitTopology.WORK_QUEUE),
                queueStats(RabbitTopology.DEAD_LETTER_QUEUE),
                workerActivity());
    }

    private OverviewResponse.QueueStats queueStats(String queue) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queue);
            if (info == null) {
                return new OverviewResponse.QueueStats(queue, true, 0L, 0);
            }
            return new OverviewResponse.QueueStats(queue, true, info.getMessageCount(), info.getConsumerCount());
        } catch (AmqpException e) {
            log.warn("RabbitMQ unreachable while reading queue {}: {}", queue, e.getMessage());
            return OverviewResponse.QueueStats.unreachable(queue);
        }
    }

    private List<OverviewResponse.WorkerActivity> workerActivity() {
        return jdbc.sql("""
                        WITH recent AS (
                            SELECT worker_id, count(*) AS attempts, max(finished_at) AS last_attempt_at
                              FROM delivery_attempts
                             WHERE started_at > now() - interval '10 minutes'
                             GROUP BY worker_id
                        ), in_flight AS (
                            SELECT claimed_by AS worker_id,
                                   count(*) AS in_flight,
                                   count(*) FILTER (WHERE claim_expires_at < now()) AS expired
                              FROM deliveries
                             WHERE status = 'IN_PROGRESS'
                             GROUP BY claimed_by
                        )
                        SELECT coalesce(r.worker_id, f.worker_id) AS worker_id,
                               coalesce(r.attempts, 0)            AS attempts,
                               coalesce(f.in_flight, 0)           AS in_flight,
                               coalesce(f.expired, 0)             AS expired,
                               r.last_attempt_at
                          FROM recent r
                          FULL OUTER JOIN in_flight f ON f.worker_id = r.worker_id
                         ORDER BY worker_id
                        """)
                .query((rs, rowNum) -> {
                    OffsetDateTime lastAttempt = rs.getObject("last_attempt_at", OffsetDateTime.class);
                    return new OverviewResponse.WorkerActivity(
                            rs.getString("worker_id"),
                            rs.getLong("attempts"),
                            rs.getLong("in_flight"),
                            rs.getLong("expired"),
                            lastAttempt == null ? null : lastAttempt.toInstant());
                })
                .list();
    }
}
