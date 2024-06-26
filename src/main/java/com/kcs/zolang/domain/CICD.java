package com.kcs.zolang.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "repository")
public class CICD {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "repository_id")
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "repository_name", nullable = false)
    private String repositoryName;

    @Column(name = "branch", nullable = false)
    private String branch;

    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "language_version", nullable = false)
    private String languageVersion;

    @Column(name = "build_tool", nullable = false)
    private String buildTool;

    @Column(name = "z_trigger", nullable = false)
    private String trigger;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "service_domain", nullable = true)
    private String serviceDomain;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public CICD(User user, String repositoryName, String branch, String language, String languageVersion, String buildTool, String trigger, Integer port, String serviceDomain) {
        this.user = user;
        this.repositoryName = repositoryName;
        this.createdAt = LocalDateTime.now();
        this.branch = branch;
        this.language = language;
        this.languageVersion = (languageVersion != null) ? languageVersion : "none";
        this.buildTool = (buildTool != null) ? buildTool : "none";
        this.trigger = trigger;
        this.port = port;
        this.serviceDomain = (serviceDomain != null) ? serviceDomain : "none";
    }

    public void update(String repositoryName) {
        this.repositoryName = repositoryName;
    }
}
