package com.workingdead.meet.repository;

import com.workingdead.meet.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    List<Participant> findByVoteId(Long voteId);
    Optional<Participant> findByVoteIdAndKakaoId(Long voteId, String kakaoId); // 여기!
}