package com.workingdead.meet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.workingdead.timepoll.enums.Period;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id", nullable = false)
    private Vote vote;

    @Column(name = "confirmed_date", nullable = false)
    private String confirmedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "period")
    private Period period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TimePollStatus status = TimePollStatus.ONGOING;

    @Column(name = "finalized_time")
    private LocalTime finalizedTime;

    @Column(name = "ultimatum_sent_at")
    private Instant ultimatumSentAt;

    @Column(name = "last_reminder_step")
    @Builder.Default
    private Integer lastReminderStep = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    // 추가
    @Column(name = "bot_group_key")
    private String botGroupKey;

    @OneToMany(mappedBy = "timePoll", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<TimePollEntry> entries = new ArrayList<>();
}