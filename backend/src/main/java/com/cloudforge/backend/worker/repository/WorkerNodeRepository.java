package com.cloudforge.backend.worker.repository;

import com.cloudforge.backend.worker.entity.WorkerNodeEntity;
import com.cloudforge.backend.worker.entity.WorkerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerNodeRepository extends JpaRepository<WorkerNodeEntity, UUID> {

    Optional<WorkerNodeEntity> findByWorkerId(String workerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM WorkerNodeEntity w WHERE w.workerId = :workerId")
    Optional<WorkerNodeEntity> findByWorkerIdForUpdate(@Param("workerId") String workerId);

    List<WorkerNodeEntity> findAllByOrderByWorkerIdAsc();

    List<WorkerNodeEntity> findAllByStatusInAndLastHeartbeatAtBefore(
            Collection<WorkerStatus> statuses,
            Instant threshold
    );

    long countByStatus(WorkerStatus status);
}
