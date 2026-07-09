package com.workingdead.enums;

/**
 * 참여자 수집 세션(PendingSession) 상태
 *
 * COLLECTING: 날짜범위는 정해졌고, 24시간 동안 "참여" 버튼 클릭을 모으는 중
 * FINALIZED: 24시간이 지나 Vote가 생성되고 수집이 종료됨
 */
public enum PendingSessionStatus {
    COLLECTING,
    FINALIZED
}