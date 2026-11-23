package com.cloudforge.backend.repository;

import com.cloudforge.backend.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByGithubUserId(Long githubUserId);

    Optional<UserEntity> findByUsernameIgnoreCase(String username);
}
