package com.workingdead.meet.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.workingdead.enums.VoteStatus;
import com.workingdead.meet.entity.Vote;

public interface VoteRepository extends JpaRepository<Vote, Long> {

    Optional<Vote> findByCode(String code);

    List<Vote> findByStatus(VoteStatus status);
}
