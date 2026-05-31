package com.workingdead.meet.entity;

import java.time.Instant;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
