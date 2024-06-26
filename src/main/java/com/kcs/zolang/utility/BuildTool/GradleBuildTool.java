package com.kcs.zolang.utility.BuildTool;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

@Slf4j
public class GradleBuildTool implements BuildTool {
    @Override
    public String setup(String repoDir) throws IOException, InterruptedException, ExecutionException {
        if (!new File(repoDir + "/gradlew").exists() || !new File(repoDir + "/gradle/wrapper/gradle-wrapper.jar").exists()) {
            log.info("Gradle wrapper 없음. 생성 중");
            return "cd " + repoDir + " && gradle wrapper --gradle-version 8.0.2";
        }
        else {
            return null;
        }
    }

    @Override
    public String build(String repoDir) throws IOException, InterruptedException, ExecutionException {
        return "cd " + repoDir + " && chmod +x ./gradlew && ./gradlew build -x test";
    }
}
