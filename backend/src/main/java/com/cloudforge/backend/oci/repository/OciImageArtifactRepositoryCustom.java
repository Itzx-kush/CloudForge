package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OciImageArtifactRepositoryCustom {

    Page<OciImageArtifactEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            OciArtifactVerificationStatus verificationStatus,
            String search,
            Pageable pageable
    );
}
