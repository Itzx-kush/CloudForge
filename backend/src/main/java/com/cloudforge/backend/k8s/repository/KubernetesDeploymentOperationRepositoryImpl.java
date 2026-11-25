package com.cloudforge.backend.k8s.repository;

import com.cloudforge.backend.k8s.entity.KubernetesDeploymentOperationEntity;
import com.cloudforge.backend.k8s.entity.KubernetesDeploymentStatus;
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

public class KubernetesDeploymentOperationRepositoryImpl implements KubernetesDeploymentOperationRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<KubernetesDeploymentOperationEntity> searchByWorkspace(
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            KubernetesDeploymentStatus status,
            String search,
            Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<KubernetesDeploymentOperationEntity> query =
                cb.createQuery(KubernetesDeploymentOperationEntity.class);
        Root<KubernetesDeploymentOperationEntity> root = query.from(KubernetesDeploymentOperationEntity.class);
        root.fetch("project", JoinType.LEFT);
        root.fetch("environment", JoinType.LEFT);
        root.fetch("clusterConnection", JoinType.LEFT);
        root.fetch("namespaceBinding", JoinType.LEFT);
        root.fetch("ociArtifact", JoinType.LEFT);
        root.fetch("pipelineRun", JoinType.LEFT);
        root.fetch("requestedBy", JoinType.LEFT);
        root.fetch("cancelledBy", JoinType.LEFT);
        List<Predicate> predicates = buildPredicates(
                cb, root, workspaceId, projectId, environmentId, clusterId, status, search
        );
        query.select(root).where(predicates.toArray(Predicate[]::new));
        query.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

        TypedQuery<KubernetesDeploymentOperationEntity> typed = entityManager.createQuery(query);
        typed.setFirstResult((int) pageable.getOffset());
        typed.setMaxResults(pageable.getPageSize());
        List<KubernetesDeploymentOperationEntity> items = typed.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<KubernetesDeploymentOperationEntity> countRoot =
                countQuery.from(KubernetesDeploymentOperationEntity.class);
        List<Predicate> countPredicates = buildPredicates(
                cb, countRoot, workspaceId, projectId, environmentId, clusterId, status, search
        );
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(items, pageable, total);
    }

    private List<Predicate> buildPredicates(
            CriteriaBuilder cb,
            Root<KubernetesDeploymentOperationEntity> root,
            UUID workspaceId,
            UUID projectId,
            UUID environmentId,
            UUID clusterId,
            KubernetesDeploymentStatus status,
            String search
    ) {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.equal(root.get("workspace").get("id"), workspaceId));
        if (projectId != null) {
            predicates.add(cb.equal(root.get("project").get("id"), projectId));
        }
        if (environmentId != null) {
            predicates.add(cb.equal(root.get("environment").get("id"), environmentId));
        }
        if (clusterId != null) {
            predicates.add(cb.equal(root.get("clusterConnection").get("id"), clusterId));
        }
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (StringUtils.hasText(search)) {
            String escaped = escapeLike(search.trim().toLowerCase(Locale.ROOT));
            String pattern = "%" + escaped + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(root.get("deploymentName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("namespaceName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("manifestDigest")), pattern, '\\'),
                    cb.like(cb.lower(root.get("immutableImageReference")), pattern, '\\'),
                    cb.like(cb.lower(root.get("sourceCommitSha")), pattern, '\\')
            ));
        }
        return predicates;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
