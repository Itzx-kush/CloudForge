package com.cloudforge.backend.github;

import java.util.List;

public interface GitHubApiClient {

    GitHubInstallationDetails getInstallation(long githubInstallationId);

    List<GitHubRepositoryMetadata> listAllInstallationRepositories(long githubInstallationId);

    List<GitHubWorkflowMetadata> listRepositoryWorkflows(
            long githubInstallationId,
            String owner,
            String repo
    );

    List<GitHubWorkflowRunMetadata> listRepositoryWorkflowRuns(
            long githubInstallationId,
            String owner,
            String repo,
            int maxRuns
    );

    List<GitHubWorkflowRunMetadata> listWorkflowRunsForWorkflow(
            long githubInstallationId,
            String owner,
            String repo,
            long githubWorkflowId,
            int maxRuns
    );

    List<GitHubWorkflowJobMetadata> listWorkflowRunJobs(
            long githubInstallationId,
            String owner,
            String repo,
            long githubRunId
    );
}
