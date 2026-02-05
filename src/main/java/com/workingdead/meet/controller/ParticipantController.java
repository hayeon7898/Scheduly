package com.workingdead.meet.controller;

import com.workingdead.meet.application.VoteApplicationService;
import com.workingdead.meet.dto.*;
import com.workingdead.meet.entity.*;
import com.workingdead.meet.repository.*;
import com.workingdead.meet.service.*;
import com.workingdead.meet.dto.PriorityDtos.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;
import org.springframework.util.ReflectionUtils;

@Tag(name = "Participant", description = "참여자 관리 API")
@RestController
@RequestMapping("")
public class ParticipantController {
    private final ParticipantService participantService;
    private final PriorityService priorityService;
    private final ParticipantRepository participantRepository;
    private final VoteApplicationService voteApplicationService;

    public ParticipantController(
            ParticipantService participantService, 
            PriorityService priorityService,
            ParticipantRepository participantRepository,
            VoteApplicationService voteApplicationService) {
        this.participantService = participantService; 
        this.priorityService = priorityService;
        this.participantRepository = participantRepository;
        this.voteApplicationService = voteApplicationService;
    }

    // 0.2 참여자 추가/삭제
    @Operation(
            summary = "참여자 추가",
            description = "특정 투표에 새로운 참여자를 추가합니다. displayName을 기반으로 참여자 칩이 생성됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "참여자 추가 성공",
                    content = @Content(schema = @Schema(implementation = ParticipantDtos.ParticipantRes.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (displayName이 비어있는 경우)", content = @Content),
            @ApiResponse(responseCode = "404", description = "투표를 찾을 수 없음", content = @Content)
    })
    @PostMapping("/votes/{voteId}/participants")
    public ResponseEntity<ParticipantDtos.ParticipantRes> add(
            @PathVariable Long voteId, 
            @RequestBody @Valid ParticipantDtos.CreateParticipantReq req) {
        var res = participantService.add(voteId, req.displayName());
        return ResponseEntity.ok(res);
    }

    @Operation(
            summary = "참여자 삭제",
            description = "특정 참여자를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "참여자 삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "참여자를 찾을 수 없음", content = @Content)
    })
    @DeleteMapping("/participants/{participantId}")
    public ResponseEntity<Void> remove(@PathVariable Long participantId) {
        participantService.remove(participantId);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "참여자 정보 수정",
            description = "참여자의 displayName 등 기본 정보를 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "참여자 수정 성공",
                    content = @Content(schema = @Schema(implementation = ParticipantDtos.ParticipantRes.class))),
            @ApiResponse(responseCode = "404", description = "참여자를 찾을 수 없음", content = @Content)
    })
    @PatchMapping("/participants/{participantId}")
    public ResponseEntity<ParticipantDtos.ParticipantRes> updateParticipant(
            @PathVariable Long participantId,
            @Valid @RequestBody ParticipantDtos.UpdateParticipantReq request) {

        ParticipantDtos.ParticipantRes response =
                participantService.updateParticipant(participantId, request);
        return ResponseEntity.ok(response);
    }
    
    @Operation(
            summary = "참여자 목록 조회 (로그인 칩)",
            description = "어드민이 등록한 참여자 목록을 읽어와 로그인 칩 형태로 제공합니다. " +
                          "현재 로그인한 참여자는 loggedIn 상태로 표시됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "참여자 목록 조회 성공"),
            @ApiResponse(responseCode = "404", description = "투표를 찾을 수 없음")
    })
    @GetMapping("/votes/{voteId}/participants")
    public ResponseEntity<List<ParticipantDtos.ParticipantRes>> getParticipants(
            @PathVariable Long voteId,
            @RequestParam(required = false) Long currentParticipantId
    ) {
        List<ParticipantDtos.ParticipantRes> participants = 
                participantService.getParticipantsForVote(voteId, currentParticipantId);
        return ResponseEntity.ok(participants);
    }
    
    /**
     * PATCH /participants/{id}/info
     * 참여자 정보 부분 수정 (리플렉션)
     */
    @PatchMapping("/participants/{id}/info")
public ResponseEntity<ParticipantDtos.ParticipantRes> updateParticipant(
        @PathVariable Long id,
        @RequestBody Map<String, Object> updates) {

    Participant participant = participantRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Not found"));

    updates.forEach((key, value) -> {
        Field field = ReflectionUtils.findField(Participant.class, key);
        if (field != null) {
            field.setAccessible(true);
            ReflectionUtils.setField(field, participant, value);
        }
    });

    Participant saved = participantRepository.save(participant);
    
    // DTO로 변환해서 반환!
    ParticipantDtos.ParticipantRes response = new ParticipantDtos.ParticipantRes(
        saved.getId(),
        saved.getDisplayName(),
        false  // loggedIn 상태
    );
    
    return ResponseEntity.ok(response);
}

    /**
     * POST /participants/{participantId}
     * 우선순위 설정 (최대 3개)
     */
    @PostMapping("/participants/{participantId}")
    public ResponseEntity<PriorityResponse> setPriorities(
            @PathVariable Long participantId,
            @RequestParam Long voteId,
            @Valid @RequestBody PriorityRequest request,
            @RequestParam(required = false, defaultValue = "db") String storage,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun,
            HttpSession session) {
        
        PriorityResponse response = priorityService.setPriorities(
                participantId, 
                voteId, 
                request, 
                storage, 
                dryRun, 
                session
        );
        
        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /participants/{participantId}/schedule
     * 일정 제출
     */
    @PatchMapping("/participants/{participantId}/schedule")
    public ResponseEntity<ParticipantDtos.ParticipantScheduleRes> submitSchedule(
            @PathVariable Long participantId,
            @Valid @RequestBody ParticipantDtos.SubmitScheduleReq request) {
        
        ParticipantDtos.ParticipantScheduleRes response = 
            participantService.submitSchedule(participantId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "참여자의 선택 정보 조회",
            description = "특정 참여자가 선택한 일정과 우선순위를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ParticipantDtos.ParticipantChoicesRes.class))),
            @ApiResponse(responseCode = "404", description = "참여자를 찾을 수 없음", content = @Content)
    })
    @GetMapping("/participants/{participantId}/choices")
    public ResponseEntity<ParticipantDtos.ParticipantChoicesRes> getParticipantChoices(
            @PathVariable Long participantId) {

        ParticipantDtos.ParticipantChoicesRes response =
                participantService.getParticipantChoices(participantId);

        return ResponseEntity.ok(response);
    }
}