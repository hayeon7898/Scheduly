package com.workingdead.chatbot.kakao.scheduler;

import com.workingdead.chatbot.kakao.service.KakaoTimePollNotifier;
import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.entity.TimePollStatus;
import com.workingdead.meet.repository.TimePollRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
@Slf4j
public class KakaoTimePollScheduler {

//     private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
//     private final KakaoTimePollNotifier notifier;
//     private final Map<Long, List<ScheduledFuture<?>>> tasks = new ConcurrentHashMap<>();

//     public KakaoTimePollScheduler(@Lazy KakaoTimePollNotifier notifier) {
//         this.notifier = notifier;
//     }
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final KakaoTimePollNotifier notifier;
    private final TimePollRepository timePollRepository;
    private final Map<Long, List<ScheduledFuture<?>>> tasks = new ConcurrentHashMap<>();

    public KakaoTimePollScheduler(
            @Lazy KakaoTimePollNotifier notifier,
            TimePollRepository timePollRepository) {
        this.notifier = notifier;
        this.timePollRepository = timePollRepository;
    }

    @PostConstruct
    public void restoreSchedules() {
        List<TimePoll> ongoingPolls = timePollRepository.findByStatus(TimePollStatus.ONGOING);
        log.info("[TimePollScheduler] Restoring {} schedules on startup", ongoingPolls.size());

        for (TimePoll poll : ongoingPolls) {
            String botGroupKey = poll.getBotGroupKey();
            if (botGroupKey == null || botGroupKey.isBlank()) {
                log.warn("[TimePollScheduler] No botGroupKey for timePollId={}. Skip.", poll.getId());
                continue;
            }
            restoreSchedule(poll, botGroupKey);
        }
    }

    private void restoreSchedule(TimePoll poll, String botGroupKey) {
        Long timePollId = poll.getId();
        Instant createdAt = poll.getCreatedAt();
        long elapsed = Duration.between(createdAt, Instant.now()).getSeconds();

        CopyOnWriteArrayList<ScheduledFuture<?>> list = new CopyOnWriteArrayList<>();

        // 각 스케줄 타이밍 (초 단위)
        scheduleIfRemaining(list, elapsed, 3 * 60,
                () -> notifier.shareTimePollStatus(timePollId, botGroupKey));

        scheduleIfRemaining(list, elapsed, 30 * 60,
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "30min"));

        scheduleIfRemaining(list, elapsed, 2 * 3600,
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "2hour"));

        scheduleIfRemaining(list, elapsed, 6 * 3600,
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "6hour"));

        scheduleIfRemaining(list, elapsed, 12 * 3600,
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "12hour"));

        scheduleIfRemaining(list, elapsed, 24 * 3600,
                () -> notifier.sendUltimatum(timePollId, botGroupKey));

        scheduleIfRemaining(list, elapsed, 24 * 3600 + 2,
                () -> notifier.sendUltimatumButtons(botGroupKey));

        scheduleIfRemaining(list, elapsed, 25 * 3600,
                () -> notifier.finalizeIfNoResponse(timePollId, botGroupKey));

        // 전원 투표 완료 체크는 항상 등록
        list.add(scheduler.scheduleAtFixedRate(
                () -> notifier.checkAllVoted(timePollId, botGroupKey),
                1, 1, TimeUnit.MINUTES
        ));

        tasks.put(timePollId, list);
        log.info("[TimePollScheduler] Restored: timePollId={}, elapsed={}s", timePollId, elapsed);
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
            log.info("[TimePollScheduler] Skipped already-passed schedule: target={}s", targetSeconds);
        }
    }

    public void startSchedule(Long timePollId, String botGroupKey) {
        if (tasks.containsKey(timePollId)) {
                log.warn("[TimePollScheduler] Already scheduled. Skip. timePollId={}", timePollId);
                return;
        }

        CopyOnWriteArrayList<ScheduledFuture<?>> list = new CopyOnWriteArrayList<>();

        // [FIXED] 3분 후 집계 시작 (블로킹 방지)
        list.add(scheduler.schedule(
                () -> notifier.shareTimePollStatus(timePollId, botGroupKey),
                3, TimeUnit.MINUTES
        ));

        // 독촉: 30분, 2시간, 6시간, 12시간 (블로킹 방지)
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "30min"),
                4, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "2hour"),
                5, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "6hour"),
                6, TimeUnit.MINUTES
        ));
        list.add(scheduler.schedule(
                () -> notifier.remindNonVoters(timePollId, botGroupKey, "12hour"),
                7, TimeUnit.MINUTES
        ));

        // 최후통첩: 24시간 (블로킹 방지)
        list.add(scheduler.schedule(
                () -> notifier.sendUltimatum(timePollId, botGroupKey),
                8, TimeUnit.MINUTES
        ));

        // [FIXED] final_T_buttons: 최후통첩 2초 후 (논블로킹)
        list.add(scheduler.schedule(
                () -> notifier.sendUltimatumButtons(botGroupKey),
                8* 60 + 2, TimeUnit.SECONDS  // 24시간 2초
        ));

        // 최후통첩 후 60분 → 자동 확정 (블로킹 방지)
        list.add(scheduler.schedule(
                () -> notifier.finalizeIfNoResponse(timePollId, botGroupKey),
                9, TimeUnit.MINUTES
        ));

        // [FIXED] 1분마다 전원 투표 완료 체크 (블로킹 방지)
        list.add(scheduler.scheduleAtFixedRate(
                () -> notifier.checkAllVoted(timePollId, botGroupKey),
                1, 1, TimeUnit.MINUTES
        ));

        tasks.put(timePollId, list);
        log.info("[TimePollScheduler] Schedule started: timePollId={}, botGroupKey={}", timePollId, botGroupKey);
        }

        //     public void startSchedule(Long timePollId, String botGroupKey) {
        //         if (tasks.containsKey(timePollId)) {
        //             log.warn("[TimePollScheduler] Already scheduled. Skip. timePollId={}", timePollId);
        //             return;
        //         }

        //         CopyOnWriteArrayList<ScheduledFuture<?>> list = new CopyOnWriteArrayList<>();
        //         // 3분 후 집계 시작
        //         list.add(scheduler.schedule(
        //                 () -> notifier.shareTimePollStatus(timePollId, botGroupKey),
        //                 3, TimeUnit.MINUTES
        //         ));

        //         // 3분마다 과반 체크 (3분 안에 과반 시 조기 집계)
        //         // list.add(scheduler.scheduleAtFixedRate(
        //         //         () -> notifier.checkMajorityVoted(timePollId, botGroupKey),
        //         //         1, 1, TimeUnit.MINUTES
        //         // ));

        //         // 독촉: 30분, 2시간, 6시간, 12시간
        //         list.add(scheduler.schedule(
        //                 () -> notifier.remindNonVoters(timePollId, botGroupKey, "30min"),
        //                 4, TimeUnit.MINUTES
        //         ));
        //         list.add(scheduler.schedule(
        //                 () -> notifier.remindNonVoters(timePollId, botGroupKey, "2hour"),
        //                 5, TimeUnit.MINUTES
        //         ));
        //         list.add(scheduler.schedule(
        //                 () -> notifier.remindNonVoters(timePollId, botGroupKey, "6hour"),
        //                 6, TimeUnit.MINUTES
        //         ));
        //         list.add(scheduler.schedule(
        //                 () -> notifier.remindNonVoters(timePollId, botGroupKey, "12hour"),
        //                 7, TimeUnit.MINUTES
        //         ));

        //         // 최후통첩: 24시간
        //         list.add(scheduler.schedule(
        //                 () -> notifier.sendUltimatum(timePollId, botGroupKey),
        //                 8, TimeUnit.MINUTES
        //         ));

        //         // 최후통첩 후 60분 → 자동 확정
        //         list.add(scheduler.schedule(
        //                 () -> notifier.finalizeIfNoResponse(timePollId, botGroupKey),
        //                 11, TimeUnit.MINUTES
        //         ));

        //         // 5분마다 전원 투표 완료 체크
        //         list.add(scheduler.scheduleAtFixedRate(
        //                 () -> notifier.checkAllVoted(timePollId, botGroupKey),
        //                 5, 5, TimeUnit.MINUTES
        //         ));

        //         tasks.put(timePollId, list);
        //         log.info("[TimePollScheduler] Schedule started: timePollId={}, botGroupKey={}", timePollId, botGroupKey);
        //     }

        public void stopSchedule(Long timePollId) {
            List<ScheduledFuture<?>> list = tasks.remove(timePollId);
            if (list != null) {
                list.forEach(t -> t.cancel(false));
                log.info("[TimePollScheduler] Schedule stopped: timePollId={}", timePollId);
            }
        }

        public boolean hasActiveSchedule(Long timePollId) {
            return tasks.containsKey(timePollId);
        }
}