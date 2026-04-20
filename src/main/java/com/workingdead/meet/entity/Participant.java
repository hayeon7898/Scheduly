package com.workingdead.meet.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter 
@Setter 
@NoArgsConstructor
@AllArgsConstructor 
@Builder
@Entity
@Table(name = "participant")
public class Participant {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vote_id", nullable = false)
    private Vote vote;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "kakao_id")
    private String kakaoId;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
    
    private Boolean submitted;
    
    // 일정 선택 정보
    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ParticipantSelection> selections = new ArrayList<>();
    
    // 우선순위 정보
    @OneToMany(mappedBy = "participant", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PriorityPreference> priorities = new ArrayList<>();
    
    // 편의 생성자
    public Participant(Vote vote, String displayName, String kakaoId) {
        this.vote = vote;
        this.displayName = displayName;
        this.kakaoId = kakaoId;
    }
}