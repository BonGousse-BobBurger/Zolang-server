package com.kcs.zolang.controller;

import com.kcs.zolang.annotation.UserId;
import com.kcs.zolang.dto.global.ResponseDto;
import com.kcs.zolang.dto.request.CICDRequestDto;
import com.kcs.zolang.service.CICDService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/cicd")
public class CICDController {
    private final CICDService cicdService;

    @PostMapping("")
    public ResponseDto<?> registerRepository(@UserId Long userId, @RequestBody CICDRequestDto requestDto) {
        cicdService.registerRepository(userId, requestDto);
        return ResponseDto.created(null);
    }
    @GetMapping("")
    public ResponseDto<?> getCICDs(@UserId Long userId){
        return ResponseDto.ok(cicdService.getCICDs(userId));
    }
    @GetMapping("{repository_id}")
    public ResponseDto<?> getBuildRecords(@PathVariable Long repository_id) {
        return ResponseDto.ok(cicdService.getBuildRecords(repository_id));
    }

    @DeleteMapping("{repository_id}")
    public ResponseDto<?> deleteRepository(@UserId Long userId, @PathVariable Long repository_id) {
        cicdService.deleteRepository(userId, repository_id);
        return ResponseDto.ok(null);
    }

}
