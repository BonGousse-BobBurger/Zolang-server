package com.kcs.zolang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kcs.zolang.domain.*;
import com.kcs.zolang.dto.request.CICDRequestDto;
import com.kcs.zolang.dto.request.EnvVarDto;
import com.kcs.zolang.dto.response.BuildDto;
import com.kcs.zolang.dto.response.CICDDto;
import com.kcs.zolang.dto.response.UserCICDDto;
import com.kcs.zolang.exception.CommonException;
import com.kcs.zolang.exception.ErrorCode;
import com.kcs.zolang.repository.*;
import com.kcs.zolang.utility.ClusterUtil;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.NetworkingV1Api;
import io.kubernetes.client.util.Config;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CICDService {
    private final CICDRepository cicdRepository;
    private final BuildRepository buildRepository;
    private final UserRepository userRepository;
    private final WebhookEventRepository webhookEventRepository;
    private final ClusterRepository clusterRepository;
    private final EnvVarRepository envVarRepository;
    private final RestTemplate restTemplate;
    private final ClusterUtil clusterUtil;
    private final StringEncryptor stringEncryptor;

    @Value("${github.webhook-url}")
    private String webhookUrl;

    public void registerRepository(Long userId, CICDRequestDto requestDto) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_USER));
        Cluster clusterProvidedByZolang = clusterRepository.findByProviderAndUserId("zolang", userId)
                .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_CLUSTER)); // 우선 클러스터 생성을 한 이후에만 배포 등록이 가능함

        String nickname = user.getNickname();
        String token = stringEncryptor.decrypt(user.getGithubAccessToken());

        String apiUrl = String.format("https://api.github.com/repos/%s/%s/hooks", nickname, requestDto.repoName());
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("name", "web");
            body.put("active", true);
            body.put("events", requestDto.trigger());

            Map<String, String> config = new HashMap<>();
            config.put("url", webhookUrl);
            config.put("content_type", "json");

            body.put("config", config);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "token " + token);
            headers.set("X-GitHub-Api-Version", "2022-11-28");
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(apiUrl, entity, String.class);

            CICD cicd = CICD.builder()
                    .user(user)
                    .repositoryName(requestDto.repoName())
                    .branch(requestDto.branch())
                    .language(requestDto.language())
                    .languageVersion(requestDto.version() != null ? requestDto.version() : "none")
                    .buildTool(requestDto.buildTool() != null ? requestDto.buildTool() : "none")
                    .trigger(String.join(",", requestDto.trigger()))
                    .port(requestDto.port())
                    .serviceDomain(requestDto.serviceDomain() != null ? requestDto.serviceDomain() : "none")
                    .build();
            cicdRepository.save(cicd);
            if(requestDto.envVars() != null && !requestDto.envVars().isEmpty()){
                for (EnvVarDto envVarDto : requestDto.envVars()){
                    EnvironmentVariable environmentVariable = EnvironmentVariable.builder()
                            .key(envVarDto.key())
                            .value(envVarDto.value())
                            .CICD(cicd)
                            .build();
                    envVarRepository.save(environmentVariable);
                }
            }
            Build build = Build.builder()
                            .CICD(cicd)
                            .buildStatus("building")
                            .lastCommitMessage("init")
                            .buildNumber(1)
                            .build();
            buildRepository.save(build);

            try {
                List<EnvironmentVariable> environmentVariables = null;
                if(!envVarRepository.findByCICDId(cicd.getId()).isEmpty()){
                    environmentVariables = envVarRepository.findByCICDId(cicd.getId());
                }
                UserCICDDto userCICDDto = UserCICDDto.fromEntity(user);
                CICDDto cicdDto = CICDDto.fromEntity(cicd,Build.builder().build());
                clusterUtil.runPipeline(cicdDto, environmentVariables, clusterProvidedByZolang, userCICDDto, true).get();  // 비동기 작업 완료 대기
                build.update("success");
                buildRepository.save(build);
            } catch (Exception e) {
                build.update("failed");
                buildRepository.save(build);
                throw new CommonException(ErrorCode.PIPELINE_ERROR);
            }
        } catch (HttpClientErrorException e) {
            throw new CommonException(ErrorCode.FAILED_CREATE_WEBHOOK);
        }
    }

    public void handleGithubWebhook(Map<String, Object> payload, String eventType, String eventId) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.convertValue(payload, JsonNode.class);

            if (eventId == null || eventId.isEmpty()) {
                throw new CommonException(ErrorCode.INVALID_PAYLOAD);
            }

            if (webhookEventRepository.findByEventId(eventId).isPresent()) {
                return;
            }

            // Save the new event ID
            WebhookEvent webhookEvent = WebhookEvent.builder()
                            .eventId(eventId)
                            .receivedAt(LocalDateTime.now())
                            .build();
            webhookEventRepository.save(webhookEvent);

            if (!"push".equals(eventType) && !"pull_request".equals(eventType)) {
                return;
            }
            JsonNode repositoryNode = rootNode.path("repository");
            if (repositoryNode.isMissingNode() || !repositoryNode.has("name")) {
                throw new CommonException(ErrorCode.INVALID_PAYLOAD);
            }
            String repoName = repositoryNode.path("name").asText();

            String branch;
            String lastCommitMessage;

            if ("push".equals(eventType)) {
                branch = rootNode.path("ref").asText().replace("refs/heads/", "");
                lastCommitMessage = rootNode.path("head_commit").path("message").asText();
            } else {
                JsonNode pullRequestNode = rootNode.path("pull_request");
                branch = pullRequestNode.path("head").path("ref").asText();
                lastCommitMessage = pullRequestNode.path("head").path("sha").asText();
            }

            CICD cicd = cicdRepository.findByRepositoryName(repoName)
                    .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_REPOSITORY));
            if (!cicd.getBranch().equals(branch)) {
                return;
            }

            Long userId = cicd.getUser().getId();
            UserCICDDto userCICDDto = UserCICDDto.fromEntity(cicd.getUser());

            Cluster clusterProvidedByZolang = clusterRepository.findByProviderAndUserId("zolang", userId)
                    .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_CLUSTER));
            Build build = Build.builder()
                    .CICD(cicd)
                    .lastCommitMessage(lastCommitMessage)
                    .buildNumber(buildRepository.findBuildNumberByCICD(cicd) + 1)
                    .buildStatus("building")
                    .build();
            buildRepository.save(build);
            try {
                List<EnvironmentVariable> environmentVariables = envVarRepository.findByCICDId(cicd.getId());
                CICDDto cicdDto = CICDDto.fromEntity(cicd, build);
                log.info("Environment variables: {}", environmentVariables);
                clusterUtil.runPipeline(cicdDto, environmentVariables, clusterProvidedByZolang, userCICDDto, false).get();  // 비동기 작업 완료 대기
                build.update("success");
                buildRepository.save(build);
            } catch (Exception e) {
                build.update("failed");
                buildRepository.save(build);
                throw new CommonException(ErrorCode.PIPELINE_ERROR);
            }
        } catch (Exception e) {
            throw new CommonException(ErrorCode.FAILED_PROCESS_WEBHOOK);
        }
    }

    public List<CICDDto> getCICDs(Long userId) {
        List<CICD> cicdList = cicdRepository.findByUserId(userId);
        return cicdList.stream().map(cicd -> {
            Build lastBuild = buildRepository.findTopByCICDOrderByCreatedAtDesc(cicd)
                    .orElse(Build.builder().build());
            return CICDDto.fromEntity(cicd, lastBuild);
        }).toList();
    }
    public List<BuildDto> getBuildRecords(Long cicdId) {
        CICD cicd = cicdRepository.findById(cicdId)
                .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_REPOSITORY));
        List<Build> buildList = buildRepository.findByCICD(cicd);
        return buildList.stream().map(BuildDto::fromEntity).toList();
    }

    public void deleteRepository(Long userId, Long cicdId) {
        Cluster cluster = clusterRepository.findByProviderAndUserId("zolang", userId)
                .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_CLUSTER));

        ApiClient client = Config.fromToken("https://" +cluster.getDomainUrl(), cluster.getSecretToken(),false);
        Configuration.setDefaultApiClient(client);

        CICD cicd = cicdRepository.findById(cicdId)
                .orElseThrow(() -> new CommonException(ErrorCode.NOT_FOUND_REPOSITORY));
        List<Build> buildList = buildRepository.findByCICD(cicd);

        String repoName = cicd.getRepositoryName().toLowerCase().replaceAll("[^a-zA-Z0-9]", "");

        buildRepository.deleteAll(buildList);
        if (!cicd.getUser().getId().equals(userId)) {
            throw new CommonException(ErrorCode.NOT_FOUND_REPOSITORY);
        }
        cicdRepository.delete(cicd);
        CoreV1Api coreV1Api = new CoreV1Api();
        CoreV1Api.APIdeleteNamespacedServiceRequest deleteServiceRequest = coreV1Api.deleteNamespacedService(repoName + "-service", "default");
        // Service 삭제
        try {
            deleteServiceRequest.execute();
            log.info("Service deleted: {}", repoName + "-service");
        } catch (Exception e) {
            throw new CommonException(ErrorCode.FAILED_DELETE_SERVICE);
        }
        AppsV1Api appsV1Api = new AppsV1Api();

        // Deployment 삭제
        AppsV1Api.APIdeleteNamespacedDeploymentRequest deleteDeploymentRequest = appsV1Api.deleteNamespacedDeployment(repoName, "default");
        try{
            deleteDeploymentRequest.execute();
            log.info("Deleted existing deployment: {}", repoName);
        } catch (Exception e) {
            throw new CommonException(ErrorCode.FAILED_DELETE_DEPLOYMENT);
        }

        if (!cluster.getDomainUrl().equals("none")) {
            // Ingress 삭제
            try {
                NetworkingV1Api networkingV1Api = new NetworkingV1Api();
                NetworkingV1Api.APIdeleteNamespacedIngressRequest deleteIngressRequest = networkingV1Api.deleteNamespacedIngress(repoName + "-ingress", "default");
                deleteIngressRequest.execute();
            } catch (Exception e) {
                throw new CommonException(ErrorCode.FAILED_DELETE_INGRESS);
            }
        }

    }
}
