package com.workingdead.chatbot.kakao.scheduler;

import com.workingdead.chatbot.kakao.service.KakaoNotifier;
import com.workingdead.chatbot.kakao.service.KakaoNotifier.RemindTiming;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 카카오 챗봇용 스케줄러
 *
 * Discord WendyScheduler와 동일한 타이밍으로 알림을 전송합니다.
 * sessionKey(그룹챗이면 botGroupKey, 개인챗이면 userKey) 기반으로 스케줄을 관리합니다.
 **/
@Component
@Slf4j
public class KakaoWendyScheduler {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final KakaoNotifier notifier;
    private final Map<String, List<ScheduledFuture<?>>> userTasks = new ConcurrentHashMap<>();

    public KakaoWendyScheduler(KakaoNotifier notifier) {
        this.notifier = notifier;
    }

    /**
     * 스케줄 시작 (투표 생성 후 호출)
     */
    public void startSchedule(String sessionKey) {
        stopSchedule(sessionKey);

        CopyOnWriteArrayList<ScheduledFuture<?>> tasks = new CopyOnWriteArrayList<>();

        // 1) 결과 집계 시작: 3분
        tasks.add(scheduler.schedule(
                () -> notifier.shareVoteStatus(sessionKey),
                3, TimeUnit.MINUTES
        ));

        // 2) 미투표자 독촉
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, RemindTiming.MIN_30),
                30, TimeUnit.MINUTES
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, RemindTiming.HOUR_2),
                2, TimeUnit.HOURS
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, RemindTiming.HOUR_6),
                6, TimeUnit.HOURS
        ));
        tasks.add(scheduler.schedule(
                () -> notifier.remindNonVoters(sessionKey, RemindTiming.HOUR_12),
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
                25, TimeUnit.HOURS // 24h + 1h
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