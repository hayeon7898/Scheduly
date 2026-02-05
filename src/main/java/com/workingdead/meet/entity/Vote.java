package com.workingdead.meet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.*;
import java.util.*;


@Getter @Setter @AllArgsConstructor @Builder
@Entity
@Table(name = "vote")
public class Vote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // 투표(블록) 이름

    @Column(unique = true, nullable = false, updatable = false)
    private String code; // 공유용 짧은 코드 (링크)

    private LocalDate startDate; // 선택 범위 시작
    private LocalDate endDate; // 선택 범위 끝

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "vote", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Participant> participants = new ArrayList<>();

    public Vote() {}
    public Vote(String name, String code) {
        this.name = name;
        this.code = code;
    }

    // getters/setters
    public void setDateRange(LocalDate start, LocalDate end) { this.startDate = start; this.endDate = end; }
}
