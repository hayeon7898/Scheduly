package com.workingdead.meet.controller;

import com.workingdead.meet.dto.request.TimePollCreateRequest;
import com.workingdead.meet.dto.request.TimePollSubmitRequest;
import com.workingdead.meet.dto.response.TimePollResponse;
import com.workingdead.meet.dto.response.TimePollStatusResponse;
import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.service.TimePollService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/time-polls")
@RequiredArgsConstructor
public class TimePollController {

    private final TimePollService timePollService;

    /**
     * API #1 - 시간 투표 생성
     * POST /time-polls
     */
    @PostMapping
    public ResponseEntity<Map<String, Long>> create(@RequestBody TimePollCreateRequest request) {
        TimePoll timePoll = timePollService.create(request);
        return ResponseEntity.ok(Map.of("timePollId", timePoll.getId()));
    }

    /**
     * API #2 - 투표 페이지 조회
     * GET /time-polls/{pollId}?participantId=123
     */
    @GetMapping("/{pollId}")
    public ResponseEntity<TimePollResponse> getTimePoll(
            @PathVariable Long pollId,
            @RequestParam Long participantId) {
        return ResponseEntity.ok(timePollService.getTimePoll(pollId, participantId));
    }

    /**
     * API #3 - 투표 제출
     * POST /time-polls/{pollId}/submit
     */
    @PostMapping("/{pollId}/submit")
    public ResponseEntity<TimePollStatusResponse> submit(
            @PathVariable Long pollId,
            @RequestBody TimePollSubmitRequest request) {
        return ResponseEntity.ok(timePollService.submit(pollId, request));
    }

    /**
     * API #4 - 투표 현황 조회
     * GET /time-polls/{pollId}/status
     */
    @GetMapping("/{pollId}/status")
    public ResponseEntity<TimePollStatusResponse> getStatus(@PathVariable Long pollId) {
        return ResponseEntity.ok(timePollService.getStatus(pollId));
    }

    /**
     * API #5 - "저도 그때 좋아요" 수락
     * POST /time-polls/{pollId}/accept
     */
    @PostMapping("/{pollId}/accept")
    public ResponseEntity<TimePollStatusResponse> accept(
            @PathVariable Long pollId,
            @RequestParam Long participantId) {
        return ResponseEntity.ok(timePollService.accept(pollId, participantId));
    }

    /**
     * API #6 - 투표 확정 (내부용이지만 수동 트리거 가능)
     * POST time-polls/{pollId}/finalize
     */
    @PostMapping("/{pollId}/finalize")
    public ResponseEntity<Void> finalize(@PathVariable Long pollId) {
        timePollService.finalize(pollId);
        return ResponseEntity.ok().build();
    }

    /**
     * API #7 - 투표 ID 조회
     * GET /time-polls/{pollId}/vote
     */
    @GetMapping("/{pollId}/vote")
    public ResponseEntity<Map<String, Long>> getVoteId(@PathVariable Long pollId) {
        Long voteId = timePollService.getVoteIdByPollId(pollId);
        return ResponseEntity.ok(Map.of("voteId", voteId));
    }
}