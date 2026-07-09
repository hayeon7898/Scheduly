package com.workingdead.meet.repository;

import com.workingdead.enums.PendingSessionStatus;
import com.workingdead.meet.entity.PendingSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PendingSessionRepository extends JpaRepository<PendingSession, Long> {

    Optional<PendingSession> findBySessionKeyAndStatus(String sessionKey, PendingSessionStatus status);

    List<PendingSession> findByStatus(PendingSessionStatus status);
}