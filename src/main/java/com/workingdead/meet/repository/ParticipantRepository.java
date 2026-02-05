package com.workingdead.meet.repository;

import com.workingdead.meet.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {
    List<Participant> findByVoteId(Long voteId);
}
