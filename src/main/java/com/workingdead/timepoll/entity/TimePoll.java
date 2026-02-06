package com.workingdead.meet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "time_poll")
public class TimePoll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 Vote(날짜 투표)에서 파생됐는지
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id", nullable = false)
    private Vote vote;

    // 확정된 날짜 (날짜 투표 결과)
    @Column(name = "confirmed_date", nullable = false)
    private String confirmedDate; // "1월 28일 저녁" 같은 형태

    // 투표 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TimePollStatus status = TimePollStatus.ONGOING;

    // 최종 확정된 시간 (투표 완료 후)
    @Column(name = "finalized_time")
    private LocalTime finalizedTime;

    // 최후통첩 발송 시각
    @Column(name = "ultimatum_sent_at")
    private Instant ultimatumSentAt;

    // 마지막 독촉 단계 (0=안함, 1=30분, 2=2시간, 3=6시간, 4=12시간)
    @Column(name = "last_reminder_step")
    @Builder.Default
    private Integer lastReminderStep = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 개별 투표 응답들
    @OneToMany(mappedBy = "timePoll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TimePollEntry> entries = new ArrayList<>();
}