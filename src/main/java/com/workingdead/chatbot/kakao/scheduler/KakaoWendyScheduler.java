package com.workingdead.chatbot.kakao.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.workingdead.chatbot.kakao.service.KakaoNotifier;
import com.workingdead.chatbot.kakao.service.KakaoWendyService;
import com.workingdead.enums.VoteStatus;
import com.workingdead.meet.entity.Vote;
import com.workingdead.meet.repository.VoteRepository;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class KakaoWendyScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final KakaoNotifier notifier;
    private final VoteRepository voteRepository;
    private final KakaoWendyService kakaoWendyService;
    private final Map<String, List<ScheduledFuture<?>>> userTasks = new ConcurrentHashMap<>();

    public KakaoWendyScheduler(
            @Lazy KakaoNotifier notifier,
            VoteRepository voteRepository,
            @Lazy KakaoWendyService kakaoWendyService) {
        this.notifier = notifier;
        this.voteRepository = voteRepository;
        this.kakaoWendyService = kakaoWendyService;
    }

    @PostConstruct
    public void restoreSchedules() {
        List<Vote> ongoingVotes = voteRepository.findByStatus(VoteStatus.ONGOING);
        log.info("[WendyScheduler] Restoring {} schedules on startup", ongoingVotes.size());

        for (Vote vote : ongoingVotes) {
            String botGroupKey = vote.getBotGroupKey();
            if (botGroupKey == null || botGroupKey.isBlank()) {
                log.warn("[WendyScheduler] No botGroupKey for voteId={}. Skip.", vote.getId());
                continue;
            }
            restoreSchedule(vote, botGroupKey);
            // 인메모리 맵 복구
            kakaoWendyService.restoreVoteMapping(vote.getId(), botGroupKey);
        }
    }

    private void restoreSchedule(Vote vote, String sessionKey) {
        Instant createdAt = vote.getCreatedAt();
        long elapsed = Duration.between(createdAt, Instant.now()).getSeconds();

        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

        scheduleIfRemaining(tasks, elapsed, 3 * 60,
                () -> notifier.shareVoteStatus(sessionKey));

        scheduleIfRemaining(tasks, elapsed, 30 * 60,
                () -> notifier.remindNonVoters(sessionKey, "30min"));

        scheduleIfRemaining(tasks, elapsed, 2 * 3600,
                () -> notifier.remindNonVoters(sessionKey, "2hour"));

        scheduleIfRemaining(tasks, elapsed, 6 * 3600,
                () -> notifier.remindNonVoters(sessionKey, "6hour"));

        scheduleIfRemaining(tasks, elapsed, 12 * 3600,
                () -> notifier.remindNonVoters(sessionKey, "12hour"));

        scheduleIfRemaining(tasks, elapsed, 24 * 3600,
                () -> notifier.sendFinalNotice(sessionKey));

        scheduleIfRemaining(tasks, elapsed, 25 * 3600,
                () -> notifier.finalizeIfNoResponse(sessionKey));

        tasks.add(scheduler.scheduleAtFixedRate(
                () -> notifier.checkAllVoted(sessionKey),
                5, 5, TimeUnit.MINUTES
        ));

        userTasks.put(sessionKey, tasks);
        log.info("[WendyScheduler] Restored: sessionKey={}, elapsed={}s", sessionKey, elapsed);
    }

    private void scheduleIfRemaining(
            List<ScheduledFuture<?>> list,
            long elapsedSeconds,
            long targetSeconds,
            Runnable task) {
        long remaining = targetSeconds - elapsedSeconds;
        if (remaining > 0) {
            list.add(scheduler.schedule(task, remaining, TimeUnit.SECONDS));
        } else {
            log.info("[WendyScheduler] Skipped already-passed schedule: target={}s", targetSeconds);
        }
    }

    /**
     * 스케줄 시작 (투표 생성 후 호출)
     */
    public void startSchedule(String sessionKey) {
        if (userTasks.containsKey(sessionKey)) {
            log.warn("[Scheduler] Already in memory. Skip. sessionKey={}", sessionKey);
            return;
        }

        stopSchedule(sessionKey);

        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

        // 1) 결과 집계 시작: 3분
        tasks.add(scheduler.schedule(
                () -> notifier.shareVoteStatus(sessionKey),
                3, TimeUnit.MINUTES
        ));

        // 2) 미투표자 독촉
        // 테스트용 - 나중에 30분으로 되돌리기!
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, "30min"),
                30, TimeUnit.MINUTES // 30 → 1
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, "2hour"),
                2, TimeUnit.HOURS
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, "6hour"),
                6, TimeUnit.HOURS
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, "12hour"),
                12, TimeUnit.HOURS
        ));

        // 3) 최후통첩: 24시간
        tasks.add(scheduler.schedule(
                () -> notifier.sendFinalNotice(sessionKey),
                24, TimeUnit.HOURS
        ));

        // 4) 최후통첩 후 60분 내 미응답 시 확정
        tasks.add(scheduler.schedule(
                () -> notifier.finalizeIfNoResponse(sessionKey),
                25, TimeUnit.HOURS
        ));

        // 5) 전원 투표 완료 체크 (1분마다)
        tasks.add(scheduler.scheduleAtFixedRate(
                () -> notifier.checkAllVoted(sessionKey),
                1, 1, TimeUnit.MINUTES
        ));

        userTasks.put(sessionKey, tasks);
        log.info("[Kakao Scheduler] Schedule started: {}", sessionKey);
    }

    /**
     * 스케줄 중지 (세션 종료 또는 재투표 시 호출)
     */
    public void stopSchedule(String sessionKey) {
        List<ScheduledFuture<?>> tasks = userTasks.remove(sessionKey);
        if (tasks != null) {
            tasks.forEach(task -> task.cancel(false));
            log.info("[Kakao Scheduler] Schedule stopped: {}", sessionKey);
        }
    }

    /**
     * 활성 스케줄 여부 확인
     */
    public boolean hasActiveSchedule(String sessionKey) {
        return userTasks.containsKey(sessionKey);
    }
}
