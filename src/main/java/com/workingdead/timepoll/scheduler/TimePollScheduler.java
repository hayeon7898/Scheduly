package com.workingdead.meet.scheduler;

import com.workingdead.meet.entity.TimePoll;
import com.workingdead.meet.entity.TimePollStatus;
import com.workingdead.meet.repository.TimePollRepository;
import com.workingdead.meet.service.TimePollService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TimePollScheduler {

    private final TimePollRepository timePollRepository;
    private final TimePollService timePollService;

    // 독촉 단계별 경과 시간 (분)
    private static final long AGGREGATE_MINUTES = 3;
    private static final long REMIND_1_MINUTES = 30;
    private static final long REMIND_2_MINUTES = 120;    // 2시간
    private static final long REMIND_3_MINUTES = 360;    // 6시간
    private static final long REMIND_4_MINUTES = 720;    // 12시간
    private static final long ULTIMATUM_MINUTES = 1440;  // 24시간
    private static final long AUTO_FINALIZE_MINUTES = 60; // 최후통첩 후 60분

    /**
     * 1분마다 실행 - 모든 진행 중인 투표를 체크
     */
    @Scheduled(fixedRate = 60000)
    public void checkTimePoll() {
        List<TimePoll> ongoingPolls = timePollRepository.findByStatusAndCreatedAtBefore(
                TimePollStatus.ONGOING, Instant.now());

        for (TimePoll poll : ongoingPolls) {
            try {
                processOngoingPoll(poll);
            } catch (Exception e) {
                log.error("[TimePollScheduler] Failed to process pollId={}: {}", poll.getId(), e.getMessage());
            }
        }

        // 최후통첩 상태 - 자동 확정 체크
        List<TimePoll> ultimatumPolls = timePollRepository.findByStatusAndUltimatumSentAtBefore(
                TimePollStatus.ULTIMATUM,
                Instant.now().minus(Duration.ofMinutes(AUTO_FINALIZE_MINUTES)));

        for (TimePoll poll : ultimatumPolls) {
            try {
                processAutoFinalize(poll);
            } catch (Exception e) {
                log.error("[TimePollScheduler] Auto-finalize failed pollId={}: {}", poll.getId(), e.getMessage());
            }
        }
    }

    /**
     * ONGOING 상태 투표 처리
     * - 3분 집계
     * - 30분 / 2시간 / 6시간 / 12시간 독촉
     * - 24시간 최후통첩
     */
    private void processOngoingPoll(TimePoll poll) {
        long minutesElapsed = Duration.between(poll.getCreatedAt(), Instant.now()).toMinutes();
        List<String> pendingNames = timePollService.getPendingNames(poll.getId());

        // 미투표자가 없으면 스킵 (전원 투표 완료는 submit에서 처리)
        if (pendingNames.isEmpty()) {
            return;
        }

        // --- 3분 집계 ---
        if (minutesElapsed >= AGGREGATE_MINUTES && poll.getLastReminderStep() < 1) {
            sendAggregateMessage(poll);
            poll.setLastReminderStep(1);
            timePollRepository.save(poll);
            return;
        }

        // 3분 전인데 과반 이상 투표 시 집계
        if (minutesElapsed < AGGREGATE_MINUTES && poll.getLastReminderStep() < 1) {
            if (timePollService.isMajoritySubmitted(poll.getId())) {
                sendAggregateMessage(poll);
                poll.setLastReminderStep(1);
                timePollRepository.save(poll);
            }
            return;
        }

        // --- 24시간 최후통첩 ---
        if (minutesElapsed >= ULTIMATUM_MINUTES && poll.getLastReminderStep() < 6) {
            sendUltimatumMessage(poll, pendingNames);
            poll.setLastReminderStep(6);
            poll.setStatus(TimePollStatus.ULTIMATUM);
            poll.setUltimatumSentAt(Instant.now());
            timePollRepository.save(poll);
            return;
        }

        // --- 독촉 메시지 ---
        if (minutesElapsed >= REMIND_4_MINUTES && poll.getLastReminderStep() < 5) {
            sendReminderMessage(poll, pendingNames, 5);
        } else if (minutesElapsed >= REMIND_3_MINUTES && poll.getLastReminderStep() < 4) {
            sendReminderMessage(poll, pendingNames, 4);
        } else if (minutesElapsed >= REMIND_2_MINUTES && poll.getLastReminderStep() < 3) {
            sendReminderMessage(poll, pendingNames, 3);
        } else if (minutesElapsed >= REMIND_1_MINUTES && poll.getLastReminderStep() < 2) {
            sendReminderMessage(poll, pendingNames, 2);
        }
    }

    /**
     * 최후통첩 60분 경과 - 자동 확정
     */
    private void processAutoFinalize(TimePoll poll) {
        List<String> pendingNames = timePollService.getPendingNames(poll.getId());

        log.info("[TimePollScheduler] Auto-finalize pollId={}, pending={}", poll.getId(), pendingNames);

        timePollService.finalize(poll.getId());

        // TODO: "00시로 확정됨" 통보 메시지 발송
    }

    /**
     * 집계 메시지 발송
     */
    private void sendAggregateMessage(TimePoll poll) {
        var status = timePollService.getStatus(poll.getId());

        if (status.getSubmittedCount() == 0) {
            log.info("[TimePollScheduler] Aggregate - no votes yet. pollId={}", poll.getId());
        } else {
            log.info("[TimePollScheduler] Aggregate - sharing status. pollId={}, voteCount={}", 
                    poll.getId(), status.getSubmittedCount());
        }

        // TODO: send chatbot message
    }

    /**
     * 독촉 메시지 발송
     */
    private void sendReminderMessage(TimePoll poll, List<String> pendingNames, int step) {
        String mentions = String.join(", ", pendingNames);

        switch (step) {
            case 2 -> // 30min
                log.info("[TimePollScheduler] Reminder 30min. pollId={}, pending={}", 
                        poll.getId(), mentions);
            case 3 -> // 2h
                log.info("[TimePollScheduler] Reminder 2h. pollId={}, pending={}", 
                        poll.getId(), mentions);
            case 4 -> // 6h
                log.info("[TimePollScheduler] Reminder 6h. pollId={}, pending={}", 
                        poll.getId(), mentions);
            case 5 -> // 12h
                log.info("[TimePollScheduler] Reminder 12h. pollId={}, pending={}", 
                        poll.getId(), mentions);
        }

        poll.setLastReminderStep(step);
        timePollRepository.save(poll);

        // TODO: send chatbot message
    }

    /**
     * 최후통첩 메시지 발송
     */
    private void sendUltimatumMessage(TimePoll poll, List<String> pendingNames) {
        String mentions = String.join(", ", pendingNames);

        log.info("[TimePollScheduler] Ultimatum sent. pollId={}, pending={}, autoFinalize in 60min", 
                poll.getId(), mentions);

        // TODO: send chatbot message with buttons
    }
}