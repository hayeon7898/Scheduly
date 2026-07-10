package com.workingdead.meet.entity;

import com.workingdead.enums.PendingSessionStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 날짜범위는 정해졌지만 아직 Vote가 생성되지 않은, "참여자 수집 중" 상태를 표현하는 임시 세션.
 *
 * 흐름: 주차 선택 → PendingSession 생성(COLLECTING) → 24시간 동안 "참여" 클릭 누적
 *      → 24시간 후 스케줄러가 Vote 생성 + Participant 등록 → status=FINALIZED
 *
 * DB에 저장하는 이유: 서버가 재시작되어도 KakaoWendyScheduler.restoreSchedules()에서
 * createdAt 기준으로 남은 시간을 재계산해 스케줄을 복구하기 위함.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pending_session")
public class PendingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 개인챗이면 userKey, 그룹챗이면 botGroupKey.
     *  주의: 전역 unique 아님 — 같은 sessionKey로 수집→완료→재시작을 반복하면 여러 행이 쌓임.
     *  "동시에 COLLECTING 상태는 하나만" 규칙은 startCollecting()에서 기존 COLLECTING 행을
     *  지우고 새로 넣는 방식으로 애플리케이션 레벨에서 보장한다. */
    @Column(name = "session_key", nullable = false)
    private String sessionKey;

    @Column(name = "bot_group_key")
    private String botGroupKey;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PendingSessionStatus status;

    /**
     * "참여" 버튼을 클릭한 사람들의 botUserKey 모음.
     * Set이라 같은 사람이 여러 번 눌러도 중복 저장되지 않음.
     */
    @ElementCollection
    @CollectionTable(name = "pending_session_participant",
            joinColumns = @JoinColumn(name = "pending_session_id"))
    @Column(name = "bot_user_key")
    private Set<String> botUserKeys = new HashSet<>();

    public PendingSession(String sessionKey, String botGroupKey, LocalDate startDate, LocalDate endDate) {
        this.sessionKey = sessionKey;
        this.botGroupKey = botGroupKey;
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdAt = Instant.now();
        this.status = PendingSessionStatus.COLLECTING;
    }
}