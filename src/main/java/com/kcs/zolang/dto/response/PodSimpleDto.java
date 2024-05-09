package com.kcs.zolang.dto.response;

import static com.kcs.zolang.service.WorkloadService.getAge;

import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
@Schema(name = "PodSimpleDto", description = "유저 Pod 정보 Dto")
public record PodSimpleDto(
    @Schema(description = "Pod 이름", example = "pod-name")
    String name,
    @Schema(description = "Pod 네임스페이스", example = "sandbox")
    String namespace,
    @Schema(description = "Pod 이미지", example = "registry.k8s.io/ingress-nginx/controller:v1.1.1")
    List<String> image,
    @Schema(description = "Pod 레이블", example = "app:nginx")
    Map<String, String> labels,
    @Schema(description = "Pod 노드", example = "node-name")
    String node,
    @Schema(description = "Pod 상태", example = "Running")
    String status,
    @Schema(description = "Pod 재시작 횟수", example = "0")
    List<Integer> restartCount,
    @Schema(description = "Pod 생성 시간", example = "1d")
    String age) {

    public static PodSimpleDto fromEntity(V1Pod pod) {
        return PodSimpleDto.builder()
            .name(pod.getMetadata().getName())
            .namespace(pod.getMetadata().getNamespace())
            .image(pod.getSpec().getContainers().stream().map(V1Container::getImage).toList())
            .labels(pod.getMetadata().getLabels())
            .restartCount(pod.getStatus().getContainerStatuses().stream()
                .map(V1ContainerStatus::getRestartCount).toList())
            .node(pod.getSpec().getNodeName())
            .age(getAge(pod.getMetadata().getCreationTimestamp().toLocalDateTime()))
            .status(pod.getStatus().getPhase())
            .build();
    }
}