package com.cloudforge.backend.orchestration.engine;

import com.cloudforge.backend.exception.BadRequestException;
import com.cloudforge.backend.exception.ConflictException;
import com.cloudforge.backend.orchestration.entity.PipelineRunEntity;
import com.cloudforge.backend.orchestration.entity.PipelineRunStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageStatus;
import com.cloudforge.backend.orchestration.entity.PipelineStageType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineStateMachineAndDagTest {

    private final StageDagValidator validator = new StageDagValidator();

    @Test
    void enforcesPipelineRunStateMachineAndImmutableTerminalStates() {
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.QUEUED, PipelineRunStatus.RUNNING)).isTrue();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.QUEUED, PipelineRunStatus.CANCELLED)).isTrue();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.RUNNING, PipelineRunStatus.SUCCEEDED)).isTrue();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.RUNNING, PipelineRunStatus.FAILED)).isTrue();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.RUNNING, PipelineRunStatus.CANCELLED)).isTrue();

        // Illegal transitions
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.QUEUED, PipelineRunStatus.SUCCEEDED)).isFalse();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.QUEUED, PipelineRunStatus.FAILED)).isFalse();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.SUCCEEDED, PipelineRunStatus.RUNNING)).isFalse();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.FAILED, PipelineRunStatus.RUNNING)).isFalse();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.CANCELLED, PipelineRunStatus.RUNNING)).isFalse();
        assertThat(PipelineStateMachine.canTransition(PipelineRunStatus.SUCCEEDED, PipelineRunStatus.CANCELLED)).isFalse();

        // Entity enforces state machine directly and records timestamps
        PipelineRunEntity run = new PipelineRunEntity();
        Instant t1 = Instant.parse("2026-11-24T04:00:00Z");
        Instant t2 = Instant.parse("2026-11-24T04:05:00Z");

        run.transitionStatus(PipelineRunStatus.RUNNING, t1, "Worker claimed", null);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.RUNNING);
        assertThat(run.getStartedAt()).isEqualTo(t1);

        run.transitionStatus(PipelineRunStatus.SUCCEEDED, t2, "All stages passed", null);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.SUCCEEDED);
        assertThat(run.getCompletedAt()).isEqualTo(t2);

        // Terminal state SUCCEEDED is immutable
        assertThatThrownBy(() -> run.transitionStatus(PipelineRunStatus.CANCELLED, t2, "Too late", null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("terminal state SUCCEEDED");
        assertThatThrownBy(() -> run.transitionStatus(PipelineRunStatus.RUNNING, t2, "Retry", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void enforcesStageStateMachineTransitions() {
        assertThat(StageStateMachine.canTransition(PipelineStageStatus.PENDING, PipelineStageStatus.READY)).isTrue();
        assertThat(StageStateMachine.canTransition(PipelineStageStatus.READY, PipelineStageStatus.RUNNING)).isTrue();
        assertThat(StageStateMachine.canTransition(PipelineStageStatus.RUNNING, PipelineStageStatus.SUCCEEDED)).isTrue();
        assertThat(StageStateMachine.canTransition(PipelineStageStatus.RUNNING, PipelineStageStatus.FAILED)).isTrue();

        assertThatThrownBy(() -> StageStateMachine.validateTransition(
                PipelineStageStatus.SUCCEEDED, PipelineStageStatus.RUNNING))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> StageStateMachine.validateTransition(
                PipelineStageStatus.PENDING, PipelineStageStatus.SUCCEEDED))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void validatesDiamondDagDeterministicallyAndRejectsCyclesDuplicatesMissingRefsAndCommands() {
        // Valid diamond DAG declared out of topological order
        StageDagValidator.ValidatedStageGraph graph = validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("deploy", "Deploy", PipelineStageType.DEPLOYMENT, List.of("package"), 300, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("package", "Package", PipelineStageType.CONTAINER_PACKAGE, List.of("test", "scan"), 600, 1, false, Map.of()),
                new StageDagValidator.StageSpecInput("source", "Source", PipelineStageType.SOURCE_PREP, List.of(), 120, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("test", "Test", PipelineStageType.TEST, List.of("source"), 600, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("scan", "Security Scan", PipelineStageType.SECURITY_SCAN, List.of("source"), 300, 0, true, Map.of("scanner", "trivy-planned"))
        ));

        assertThat(graph.orderedStages()).extracting(StageDagValidator.ValidatedStageSpec::stageKey)
                .containsExactly("source", "test", "scan", "package", "deploy");

        // Cyclic dependency rejected
        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("alpha", "Alpha", PipelineStageType.BUILD, List.of("gamma"), 300, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("beta", "Beta", PipelineStageType.TEST, List.of("alpha"), 300, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("gamma", "Gamma", PipelineStageType.DEPLOYMENT, List.of("beta"), 300, 0, false, Map.of())
        )))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cyclic dependency");

        // Self-dependency rejected
        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("build", "Build", PipelineStageType.BUILD, List.of("build"), 300, 0, false, Map.of())
        )))
                .isInstanceOf(BadRequestException.class);

        // Missing stage reference rejected
        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("build", "Build", PipelineStageType.BUILD, List.of("missing_stage"), 300, 0, false, Map.of())
        )))
                .isInstanceOf(BadRequestException.class);

        // Duplicate stage key rejected
        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("build", "Build 1", PipelineStageType.BUILD, List.of(), 300, 0, false, Map.of()),
                new StageDagValidator.StageSpecInput("build", "Build 2", PipelineStageType.TEST, List.of(), 300, 0, false, Map.of())
        )))
                .isInstanceOf(BadRequestException.class);

        // Arbitrary shell command / secret in stage metadata rejected
        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("build", "Build", PipelineStageType.BUILD, List.of(), 300, 0, false,
                        Map.of("shell_command", "rm -rf /"))
        )))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> validator.validateAndSort(List.of(
                new StageDagValidator.StageSpecInput("build", "Build", PipelineStageType.BUILD, List.of(), 300, 0, false,
                        Map.of("label", "ghp_leaked_token_12345"))
        )))
                .isInstanceOf(BadRequestException.class);

        // Unbounded stage count (>25) rejected
        List<StageDagValidator.StageSpecInput> tooMany = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            tooMany.add(new StageDagValidator.StageSpecInput("stage_" + i, "Stage " + i, PipelineStageType.BUILD, List.of(), 300, 0, false, Map.of()));
        }
        assertThatThrownBy(() -> validator.validateAndSort(tooMany))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("maximum stage limit");
    }

    @Test
    void evaluatesDependenciesAndPropagatesFailuresTransitively() {
        // 1. Initial DAG: only root stage becomes READY
        List<StageDependencyEvaluator.StageTransitionDecision> initial = StageDependencyEvaluator.evaluateProgressions(List.of(
                new StageDependencyEvaluator.StageNodeState("source", PipelineStageStatus.PENDING, List.of(), false),
                new StageDependencyEvaluator.StageNodeState("build", PipelineStageStatus.PENDING, List.of("source"), false),
                new StageDependencyEvaluator.StageNodeState("deploy", PipelineStageStatus.PENDING, List.of("build"), false)
        ));
        assertThat(initial).hasSize(1);
        assertThat(initial.get(0).stageKey()).isEqualTo("source");
        assertThat(initial.get(0).toStatus()).isEqualTo(PipelineStageStatus.READY);

        // 2. Failure propagation: required predecessor FAILED -> transitive downstream stages SKIPPED
        List<StageDependencyEvaluator.StageTransitionDecision> afterFailure = StageDependencyEvaluator.evaluateProgressions(List.of(
                new StageDependencyEvaluator.StageNodeState("source", PipelineStageStatus.SUCCEEDED, List.of(), false),
                new StageDependencyEvaluator.StageNodeState("build", PipelineStageStatus.FAILED, List.of("source"), false),
                new StageDependencyEvaluator.StageNodeState("test", PipelineStageStatus.PENDING, List.of("build"), false),
                new StageDependencyEvaluator.StageNodeState("deploy", PipelineStageStatus.PENDING, List.of("test"), false)
        ));
        assertThat(afterFailure).extracting(StageDependencyEvaluator.StageTransitionDecision::stageKey)
                .containsExactly("test", "deploy");
        assertThat(afterFailure).allMatch(d -> d.toStatus() == PipelineStageStatus.SKIPPED);

        assertThat(StageDependencyEvaluator.evaluateTerminalRunOutcome(List.of(
                new StageDependencyEvaluator.StageNodeState("source", PipelineStageStatus.SUCCEEDED, List.of(), false),
                new StageDependencyEvaluator.StageNodeState("build", PipelineStageStatus.FAILED, List.of("source"), false),
                new StageDependencyEvaluator.StageNodeState("test", PipelineStageStatus.SKIPPED, List.of("build"), false),
                new StageDependencyEvaluator.StageNodeState("deploy", PipelineStageStatus.SKIPPED, List.of("test"), false)
        ))).contains(PipelineRunStatus.FAILED);

        // 3. allowFailure=true lets downstream stage become READY when optional predecessor fails
        List<StageDependencyEvaluator.StageTransitionDecision> allowFailDecision = StageDependencyEvaluator.evaluateProgressions(List.of(
                new StageDependencyEvaluator.StageNodeState("scan", PipelineStageStatus.FAILED, List.of(), true),
                new StageDependencyEvaluator.StageNodeState("package", PipelineStageStatus.PENDING, List.of("scan"), false)
        ));
        assertThat(allowFailDecision).hasSize(1);
        assertThat(allowFailDecision.get(0).stageKey()).isEqualTo("package");
        assertThat(allowFailDecision.get(0).toStatus()).isEqualTo(PipelineStageStatus.READY);
    }
}
