package com.workingdead.meet.repository;

import com.workingdead.meet.entity.TimePollEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimePollEntryRepository extends JpaRepository<TimePollEntry, Long> {

    // 특정 투표의 모든 응답 조회
    List<TimePollEntry> findByTimePollId(Long timePollId);

    // 특정 투표 + 특정 참여자의 응답 조회 (중복 제출 체크 / 수정용)
    Optional<TimePollEntry> findByTimePollIdAndParticipantId(Long timePollId, Long participantId);

    // 특정 투표의 응답 수 (과반 체크용)
    long countByTimePollId(Long timePollId);
}