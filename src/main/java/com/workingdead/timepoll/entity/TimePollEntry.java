package com.workingdead.meet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "time_poll_entry",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = {"time_poll_id", "participant_id"})
       })
public class TimePollEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "time_poll_id", nullable = false)
    private TimePoll timePoll;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    // 선택한 시간 (예: 18:00, 16:30)
    @Column(name = "selected_time", nullable = false)
    private LocalTime selectedTime;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant submittedAt = Instant.now();
}