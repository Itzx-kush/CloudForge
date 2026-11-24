package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciArtifactVerificationStatus;
import com.cloudforge.backend.oci.entity.OciImageArtifactEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class OciImageArtifactRepositoryImpl implements OciImageArtifactRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<OciImageArtifactEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            OciArtifactVerificationStatus verificationStatus,
            String search,
            Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<OciImageArtifactEntity> query = cb.createQuery(OciImageArtifactEntity.class);
        Root<OciImageArtifactEntity> root = query.from(OciImageArtifactEntity.class);
        root.fetch("project", JoinType.LEFT);
        root.fetch("pipelineRun", JoinType.LEFT);
        root.fetch("registryConnection", JoinType.LEFT);
        List<Predicate> predicates = buildPredicates(cb, root, workspaceId, projectId, verificationStatus, search);
        query.select(root).where(predicates.toArray(Predicate[]::new));
        query.orderBy(cb.desc(root.get("publishedAt")), cb.desc(root.get("id")));

        TypedQuery<OciImageArtifactEntity> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<OciImageArtifactEntity> items = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<OciImageArtifactEntity> countRoot = countQuery.from(OciImageArtifactEntity.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, workspaceId, projectId, verificationStatus, search);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(items, pageable, total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<OciImageArtifactEntity> root,
            UUID workspaceId,
            UUID projectId,
            OciArtifactVerificationStatus verificationStatus,
            String search
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
        if (projectId != null) {
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
        }
        if (verificationStatus != null) {
            predicates.add(cb.equal(root.get("verificationStatus"), verificationStatus));
        }
        if (StringUtils.hasText(search)) {
            String escaped = escapeLike(search.trim().toLowerCase(Locale.ROOT));
            String pattern = "%" + escaped + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("imageRepository")), pattern, '\\'),
                    cb.like(cb.lower(root.get("registryHost")), pattern, '\\'),
                    cb.like(cb.lower(root.get("manifestDigest")), pattern, '\\'),
                    cb.like(cb.lower(root.get("commitSha")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.get("imageTag"), "")), pattern, '\\')
            ));
        }
        return predicates;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
