package com.cloudforge.backend.worker.repository;

import com.cloudforge.backend.worker.entity.WorkerCredentialEntity;
import com.cloudforge.backend.worker.entity.WorkerCredentialStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerCredentialRepository extends JpaRepository<WorkerCredentialEntity, UUID> {

    List<WorkerCredentialEntity> findAllByWorkerIdAndStatus(String workerId, WorkerCredentialStatus status);

    Optional<WorkerCredentialEntity> findByKeyId(String keyId);

    long countByStatus(WorkerCredentialStatus status);
}
