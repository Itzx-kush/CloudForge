package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OciBuildRecordRepositoryCustom {

    Page<OciBuildRecordEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            OciBuildStatus status,
            String search,
            Pageable pageable
    );
}
