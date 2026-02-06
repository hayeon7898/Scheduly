package com.workingdead.meet.service;

import com.workingdead.meet.dto.request.TimePollCreateRequest;
import com.workingdead.meet.dto.request.TimePollSubmitRequest;
import com.workingdead.meet.dto.response.TimePollResponse;
import com.workingdead.meet.dto.response.TimePollStatusResponse;
import com.workingdead.meet.entity.*;
import com.workingdead.meet.repository.TimePollEntryRepository;
import com.workingdead.meet.repository.TimePollRepository;
import com.workingdead.meet.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimePollService {

    private final TimePollRepository timePollRepository;
    private final TimePollEntryRepository timePollEntryRepository;
    private final VoteRepository voteRepository;

    /**
     * 시간 투표 생성 (API #1)
     */
    @Transactional
    public TimePoll create(TimePollCreateRequest request) {
        Vote vote = voteRepository.findById(request.getVoteId())
                .orElseThrow(() -> new IllegalArgumentException("Vote not found: " + request.getVoteId()));

        TimePoll timePoll = TimePoll.builder()
                .vote(vote)
                .confirmedDate(request.getConfirmedDate())
                .status(TimePollStatus.ONGOING)
                .build();

        return timePollRepository.save(timePoll);
    }

    /**
     * 투표 페이지 조회 (API #2)
     * participantId로 해당 유저의 투표 여부 + 선택값 포함
     */
    public TimePollResponse getTimePoll(Long pollId, Long participantId) {
        TimePoll timePoll = findById(pollId);
        int totalParticipants = timePoll.getVote().getParticipants().size();
        long submittedCount = timePollEntryRepository.countByTimePollId(pollId);

        // 현재 유저의 기존 투표 조회
        Optional<TimePollEntry> myEntry = timePollEntryRepository
                .findByTimePollIdAndParticipantId(pollId, participantId);

        return TimePollResponse.builder()
                .id(timePoll.getId())
                .confirmedDate(timePoll.getConfirmedDate())
                .status(timePoll.getStatus())
                .finalizedTime(timePoll.getFinalizedTime())
                .totalParticipants(totalParticipants)
                .submittedCount((int) submittedCount)
                .alreadySubmitted(myEntry.isPresent())
                .mySelection(myEntry.map(TimePollEntry::getSelectedTime).orElse(null))
                .build();
    }

    /**
     * 투표 제출 (API #3)
     * 이미 투표했으면 수정, 아니면 새로 생성
     * 전원 투표 완료 시 자동 확정 트리거
     */
    @Transactional
    public TimePollStatusResponse submit(Long pollId, TimePollSubmitRequest request) {
        TimePoll timePoll = findById(pollId);

        if (timePoll.getStatus() == TimePollStatus.FINALIZED) {
            throw new IllegalStateException("이미 확정된 투표입니다.");
        }

        Participant participant = timePoll.getVote().getParticipants().stream()
                .filter(p -> p.getId().equals(request.getParticipantId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Participant not found: " + request.getParticipantId()));

        // 기존 투표 있으면 수정, 없으면 생성
        TimePollEntry entry = timePollEntryRepository
                .findByTimePollIdAndParticipantId(pollId, participant.getId())
                .orElse(TimePollEntry.builder()
                        .timePoll(timePoll)
                        .participant(participant)
                        .build());

        entry.setSelectedTime(request.getSelectedTime());
        entry.setSubmittedAt(Instant.now());
        timePollEntryRepository.save(entry);

        // 전원 투표 완료 체크
        int totalParticipants = timePoll.getVote().getParticipants().size();
        long submittedCount = timePollEntryRepository.countByTimePollId(pollId);

        if (submittedCount >= totalParticipants) {
            finalize(pollId);
        }

        return getStatus(pollId);
    }

    /**
     * 투표 현황 조회 (API #4)
     * 늦은 시간순 정렬
     */
    public TimePollStatusResponse getStatus(Long pollId) {
        TimePoll timePoll = findById(pollId);
        List<Participant> allParticipants = timePoll.getVote().getParticipants();
        List<TimePollEntry> entries = timePollEntryRepository.findByTimePollId(pollId);

        // 투표한 사람 ID 목록
        Set<Long> submittedIds = entries.stream()
                .map(e -> e.getParticipant().getId())
                .collect(Collectors.toSet());

        // 미투표자 이름 목록
        List<String> pendingNames = allParticipants.stream()
                .filter(p -> !submittedIds.contains(p.getId()))
                .map(Participant::getDisplayName)
                .collect(Collectors.toList());

        // 늦은 시간순 정렬
        List<TimePollStatusResponse.EntryDto> entryDtos = entries.stream()
                .sorted(Comparator.comparing(TimePollEntry::getSelectedTime).reversed())
                .map(e -> TimePollStatusResponse.EntryDto.builder()
                        .displayName(e.getParticipant().getDisplayName())
                        .selectedTime(e.getSelectedTime())
                        .build())
                .collect(Collectors.toList());

        return TimePollStatusResponse.builder()
                .timePollId(timePoll.getId())
                .confirmedDate(timePoll.getConfirmedDate())
                .status(timePoll.getStatus())
                .finalizedTime(timePoll.getFinalizedTime())
                .totalParticipants(allParticipants.size())
                .submittedCount(entries.size())
                .allSubmitted(pendingNames.isEmpty())
                .entries(entryDtos)
                .pendingNames(pendingNames)
                .build();
    }

    /**
     * "저도 그때 좋아요" 수락 (API #5)
     * 최다 득표 시간으로 자동 배정. 전원 완료 시에만 확정.
     */
    @Transactional
    public TimePollStatusResponse accept(Long pollId, Long participantId) {
        TimePoll timePoll = findById(pollId);

        if (timePoll.getStatus() == TimePollStatus.FINALIZED) {
            throw new IllegalStateException("이미 확정된 투표입니다.");
        }

        LocalTime mostVotedTime = getMostVotedTime(pollId);

        Participant participant = timePoll.getVote().getParticipants().stream()
                .filter(p -> p.getId().equals(participantId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Participant not found"));

        TimePollEntry entry = timePollEntryRepository
                .findByTimePollIdAndParticipantId(pollId, participantId)
                .orElse(TimePollEntry.builder()
                        .timePoll(timePoll)
                        .participant(participant)
                        .build());

        entry.setSelectedTime(mostVotedTime);
        entry.setSubmittedAt(Instant.now());
        timePollEntryRepository.save(entry);

        // 전원 투표 완료 시에만 확정
        int totalParticipants = timePoll.getVote().getParticipants().size();
        long submittedCount = timePollEntryRepository.countByTimePollId(pollId);

        if (submittedCount >= totalParticipants) {
            finalize(pollId);
        }

        return getStatus(pollId);
    }

    /**
     * 투표 확정 (API #6)
     * 최다 득표 시간으로 확정
     */
    @Transactional
    public TimePoll finalize(Long pollId) {
        TimePoll timePoll = findById(pollId);

        if (timePoll.getStatus() == TimePollStatus.FINALIZED) {
            return timePoll;
        }

        LocalTime finalTime = getMostVotedTime(pollId);
        timePoll.setFinalizedTime(finalTime);
        timePoll.setStatus(TimePollStatus.FINALIZED);

        return timePollRepository.save(timePoll);
    }

    /**
     * 과반 투표 여부 체크 (스케줄러에서 사용)
     */
    public boolean isMajoritySubmitted(Long pollId) {
        TimePoll timePoll = findById(pollId);
        int total = timePoll.getVote().getParticipants().size();
        long submitted = timePollEntryRepository.countByTimePollId(pollId);
        return submitted > total / 2.0;
    }

    /**
     * 미투표자 이름 목록 조회 (독촉 메시지용)
     */
    public List<String> getPendingNames(Long pollId) {
        TimePoll timePoll = findById(pollId);
        List<Participant> allParticipants = timePoll.getVote().getParticipants();
        List<TimePollEntry> entries = timePollEntryRepository.findByTimePollId(pollId);

        Set<Long> submittedIds = entries.stream()
                .map(e -> e.getParticipant().getId())
                .collect(Collectors.toSet());

        return allParticipants.stream()
                .filter(p -> !submittedIds.contains(p.getId()))
                .map(Participant::getDisplayName)
                .collect(Collectors.toList());
    }

    // === private ===

    private TimePoll findById(Long pollId) {
        return timePollRepository.findById(pollId)
                .orElseThrow(() -> new IllegalArgumentException("TimePoll not found: " + pollId));
    }

    private LocalTime getMostVotedTime(Long pollId) {
        List<TimePollEntry> entries = timePollEntryRepository.findByTimePollId(pollId);

        if (entries.isEmpty()) {
            throw new IllegalStateException("투표 응답이 없습니다.");
        }

        // 시간별 득표수 → 최다 득표 시간 (동률이면 더 늦은 시간)
        return entries.stream()
                .collect(Collectors.groupingBy(TimePollEntry::getSelectedTime, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.<Map.Entry<LocalTime, Long>, Long>comparing(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElseThrow();
    }
}