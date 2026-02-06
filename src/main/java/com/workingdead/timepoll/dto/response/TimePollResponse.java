package com.workingdead.meet.dto.response;

import com.workingdead.meet.entity.TimePollStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalTime;

@Getter
@Builder
@AllArgsConstructor
public class TimePollResponse {

    private Long id;
    private String confirmedDate;
    private TimePollStatus status;
    private LocalTime finalizedTime;
    private int totalParticipants;
    private int submittedCount;
    private boolean alreadySubmitted;
    private LocalTime mySelection;
}