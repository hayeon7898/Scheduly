package com.workingdead.meet.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TimePollCreateRequest {

    private Long voteId;           // 어떤 날짜 투표에서 파생됐는지
    private String confirmedDate;  // 확정된 날짜 ("1월 28일 저녁")
}