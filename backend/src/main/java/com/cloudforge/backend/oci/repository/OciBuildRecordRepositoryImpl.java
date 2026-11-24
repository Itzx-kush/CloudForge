package com.cloudforge.backend.oci.repository;

import com.cloudforge.backend.oci.entity.OciBuildRecordEntity;
import com.cloudforge.backend.oci.entity.OciBuildStatus;
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

public class OciBuildRecordRepositoryImpl implements OciBuildRecordRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<OciBuildRecordEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            OciBuildStatus status,
            String search,
            Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<OciBuildRecordEntity> query = cb.createQuery(OciBuildRecordEntity.class);
        Root<OciBuildRecordEntity> root = query.from(OciBuildRecordEntity.class);
        root.fetch("project", JoinType.LEFT);
        root.fetch("pipelineRun", JoinType.LEFT);
        root.fetch("registryConnection", JoinType.LEFT);
        List<Predicate> predicates = buildPredicates(cb, root, workspaceId, projectId, status, search);
        query.select(root).where(predicates.toArray(Predicate[]::new));
        query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        TypedQuery<OciBuildRecordEntity> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<OciBuildRecordEntity> items = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<OciBuildRecordEntity> countRoot = countQuery.from(OciBuildRecordEntity.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, workspaceId, projectId, status, search);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(items, pageable, total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<OciBuildRecordEntity> root,
            UUID workspaceId,
            UUID projectId,
            OciBuildStatus status,
            String search
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
        if (projectId != null) {
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
        }
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (StringUtils.hasText(search)) {
            String escaped = escapeLike(search.trim().toLowerCase(Locale.ROOT));
            String pattern = "%" + escaped + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("targetImageName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("repositoryFullName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("commitSha")), pattern, '\\'),
                    cb.like(cb.lower(cb.coalesce(root.get("builtManifestDigest"), "")), pattern, '\\')
            ));
        }
        return predicates;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
