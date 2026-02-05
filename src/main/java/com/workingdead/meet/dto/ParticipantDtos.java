package com.workingdead.meet.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


public class ParticipantDtos {
    public record CreateParticipantReq(@NotBlank String displayName) {}
    public record UpdateParticipantReq(String displayName) {}
    public record ParticipantRes(Long id, String displayName, boolean loggedIn // 로그인 상태
        ) {}

    // 단순 상태 조회용 DTO (투표 여부 판단 전용)
    public record ParticipantStatusRes(
            Long id,
            String displayName,
            boolean submitted
    ) {}


    // 일정 제출 요청
    public record SubmitScheduleReq(
            @NotEmpty(message = "최소 1개 이상의 날짜를 선택해주세요")
            List<DateSlotReq> schedules,
            
            // 우선순위 (선택사항)
            List<PriorityReq> priorities
    ) {}
    
    // 날짜별 슬롯
    public record DateSlotReq(
            @NotNull LocalDate date,
            @NotEmpty List<SlotReq> slots
    ) {}
    
    // 점심/저녁 선택
    public record SlotReq(
            @NotNull String period,  // "LUNCH" or "DINNER"
            boolean selected
    ) {}
    
    // 우선순위 (선택한 것들 중에서)
    public record PriorityReq(
            @NotNull LocalDate date,
            @NotNull String period,
            @Min(1) int priorityIndex,  // 1, 2, 3...
            double weight  // 가중치 (기본값 1.0)
    ) {}

    // 일정 제출 후 상세 응답 (이름 변경!)
    public record ParticipantScheduleRes(
            Long id,
            String displayName,
            List<SelectionRes> selections,
            List<PriorityRes> priorities,
            LocalDateTime submittedAt,
            boolean submitted
    ) {}
    
    public record SelectionRes(
            LocalDate date,
            String period,
            boolean selected
    ) {}
    
    public record PriorityRes(
            LocalDate date,
            String period,
            int priorityIndex,
            double weight
    ) {}

    /**
     * 참여자의 선택 정보 조회 응답
     */
    public record ParticipantChoicesRes(
            Long participantId,
            String displayName,
            List<SelectionInfo> selections,
            List<PriorityInfo> priorities
    ) {}

    /**
     * 일정 선택 정보
     */
    public record SelectionInfo(
            Long id,
            String date,
            String period,
            boolean selected
    ) {}

    /**
     * 우선순위 정보
     */
    public record PriorityInfo(
            Long id,
            String date,
            String period,
            Integer priorityIndex,
            Double weight
    ) {}
}

