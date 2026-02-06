package com.workingdead.meet.repository;

import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.entity.TimePollStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TimePollRepository extends JpaRepository<TimePoll, Long> {

    // Vote ID로 시간 투표 조회
    Optional<TimePoll> findByVoteId(Long voteId);

    // 진행 중인 투표 중 특정 시각 이전에 생성된 것들 (스케줄러용)
    List<TimePoll> findByStatusAndCreatedAtBefore(TimePollStatus status, Instant before);

    // 최후통첩 상태에서 특정 시각 이전에 발송된 것들 (60분 타임아웃 체크용)
    List<TimePoll> findByStatusAndUltimatumSentAtBefore(TimePollStatus status, Instant before);

    // 독촉 대상 조회: 진행 중 + 특정 독촉 단계 미만
    @Query("SELECT tp FROM TimePoll tp WHERE tp.status = :status AND tp.lastReminderStep < :step AND tp.createdAt < :before")
    List<TimePoll> findRemindTargets(@Param("status") TimePollStatus status,
                                     @Param("step") int step,
                                     @Param("before") Instant before);
}