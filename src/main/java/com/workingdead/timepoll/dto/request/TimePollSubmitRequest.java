package com.workingdead.meet.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Getter
@NoArgsConstructor
public class TimePollSubmitRequest {

    private Long participantId;
    private LocalTime selectedTime;   // "18:00", "16:30"
}