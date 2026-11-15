package io.hookguard.delivery.api;

import java.time.Instant;
import java.util.List;

/**
 * Live system snapshot for the dashboard: database state, broker state and worker activity.
 *
 * @param serverTime database clock at the time of the snapshot
 */
public record OverviewResponse(
        Instant serverTime,
        StatsResponse deliveries,
        long attemptsRecorded,
        QueueStats workQueue,
        QueueStats deadLetterQueue,
        List<WorkerActivity> workers) {

    /** {@code reachable=false} when the broker could not be queried; counts are then null. */
    public record QueueStats(String name, boolean reachable, Long readyMessages, Integer consumers) {

        static QueueStats unreachable(String name) {
            return new QueueStats(name, false, null, null);
        }
    }

    /**
     * A worker seen recently, identified by the ID it writes on claims and attempts.
     *
     * @param expiredLeases claims this worker still holds whose lease has expired (it probably died)
     */
    public record WorkerActivity(String workerId, long attemptsLast10Minutes, long inFlight, long expiredLeases,
                                 Instant lastAttemptAt) {
    }
}
