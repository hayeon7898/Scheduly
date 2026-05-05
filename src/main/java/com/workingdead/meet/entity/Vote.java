package com.workingdead.meet.entity;

import com.workingdead.enums.VoteStatus;

import jakarta.persistence.*;
import lombok.*;

import java.time.*;
import java.util.*;

@Getter
@Setter
@AllArgsConstructor
@Builder
@Entity
@Table(name = "vote")
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(unique = true, nullable = false, updatable = false)
    private String code;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // 추가
    @Column(name = "bot_group_key")
    private String botGroupKey;

    // 추가
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private VoteStatus status = VoteStatus.ONGOING;

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();

    public Vote() {
    }

    public Vote(String name, String code) {
        this.name = name;
        this.code = code;
    }

    public void setDateRange(LocalDate start, LocalDate end) {
        this.startDate = start;
        this.endDate = end;
    }
}
