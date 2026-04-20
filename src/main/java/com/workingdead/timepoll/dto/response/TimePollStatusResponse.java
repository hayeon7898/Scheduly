package com.workingdead.meet.dto.response;

import com.workingdead.meet.entity.TimePollStatus;
import com.workingdead.timepoll.enums.Period;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalTime;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class TimePollStatusResponse {

    private Long timePollId;
    private String confirmedDate;
    private Period period;
    private TimePollStatus status;
    private LocalTime finalizedTime;
    private int totalParticipants;
    private int submittedCount;
    private boolean allSubmitted;
    private List<EntryDto> entries;          // 투표 현황 (늦은 시간순)
    private List<String> pendingNames;       // 미투표자 이름 목록

    @Getter
    @Builder
    @AllArgsConstructor
    public static class EntryDto {
        private String displayName;
        private LocalTime selectedTime;
    }
}