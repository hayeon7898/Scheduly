package com.workingdead.meet.service;

import com.workingdead.meet.dto.*;
import com.workingdead.meet.entity.*;
import com.workingdead.meet.repository.*;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;

@Service
@Transactional
public class ParticipantService {
    private final ParticipantRepository participantRepo;
    private final VoteRepository voteRepo;
    private final ParticipantSelectionRepository selectionRepo;      // 추가!
    private final PriorityPreferenceRepository priorityRepo;         // 추가!
    private static final String CODE_ALPHABET = "abcdefghijkmnopqrstuvwxyz23456789";
    private final SecureRandom rnd = new SecureRandom();

    // 생성자 수정
    public ParticipantService(
            ParticipantRepository participantRepo, 
            VoteRepository voteRepo,
            ParticipantSelectionRepository selectionRepo,            // 추가!
            PriorityPreferenceRepository priorityRepo) {             // 추가!
        this.participantRepo = participantRepo; 
        this.voteRepo = voteRepo;
        this.selectionRepo = selectionRepo;                          // 추가!
        this.priorityRepo = priorityRepo;                            // 추가!
    }

    public ParticipantDtos.ParticipantRes add(Long voteId, String displayName, String kakaoId) {
        Vote v = voteRepo.findById(voteId)
                .orElseThrow(() -> new NoSuchElementException("vote not found"));
        Participant p = new Participant(v, displayName, kakaoId);
        participantRepo.save(p);
        return new ParticipantDtos.ParticipantRes(p.getId(), p.getDisplayName(), false);
    }
    public ParticipantDtos.ParticipantByKakaoRes getByKakaoId(Long voteId, String kakaoId) {
        Participant p = participantRepo.findByVoteIdAndKakaoId(voteId, kakaoId)
                .orElseThrow(() -> new NoSuchElementException("participant not found"));
        return new ParticipantDtos.ParticipantByKakaoRes(p.getId(), p.getDisplayName());
    }
    public ParticipantDtos.ParticipantRes updateParticipant(Long participantId, ParticipantDtos.UpdateParticipantReq request) {
        Participant participant = participantRepo.findById(participantId)
                .orElseThrow(() -> new NoSuchElementException("Participant not found"));

        // displayName 수정 (null이 아닌 경우만)
        if (request.displayName() != null && !request.displayName().isBlank()) {
            participant.setDisplayName(request.displayName());
        }

        Participant saved = participantRepo.save(participant);

        return new ParticipantDtos.ParticipantRes(
                saved.getId(),
                saved.getDisplayName(),
                false
        );
    }

    public List<ParticipantDtos.ParticipantStatusRes> getParticipantStatusByVoteId(Long voteId) {
        return participantRepo.findByVoteId(voteId).stream()
                .map(p -> new ParticipantDtos.ParticipantStatusRes(
                        p.getId(),
                        p.getDisplayName(),
                        Boolean.TRUE.equals(p.getSubmitted()),
                        p.getKakaoId()
                ))
                .toList();
    }

    public List<Participant> getParticipants(Long voteId) {
        return participantRepo.findByVoteId(voteId);
    }

    @Transactional
    public ParticipantDtos.ParticipantRes submit(Long participantId) {
        Participant participant = participantRepo.findById(participantId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Participant not found"));

        if (Boolean.TRUE.equals(participant.getSubmitted())) {
            throw new IllegalStateException("이미 제출되었습니다.");
        }

        // markSubmitted() 대신 직접 설정
        participant.setSubmitted(true);
        participant.setSubmittedAt(LocalDateTime.now());

        return new ParticipantDtos.ParticipantRes(
                participant.getId(),
                participant.getDisplayName(),
                true
        );
    }

    public void remove(Long participantId) {
        participantRepo.deleteById(participantId);
    }

//     public List<ParticipantDtos.ParticipantRes> getParticipantsForVote(Long voteId) {
//         return participantRepo.findByVoteId(voteId).stream()
//                 .map(p -> new ParticipantDtos.ParticipantRes(
//                         p.getId(),
//                         p.getDisplayName(),
//                         false 
//                 ))
//                 .collect(Collectors.toList());
//     }

//     public List<ParticipantDtos.ParticipantRes> getParticipantsForVote(Long voteId, Long currentParticipantId) {
//         return participantRepo.findByVoteId(voteId).stream()
//                 .map(p -> new ParticipantDtos.ParticipantRes(
//                         p.getId(),
//                         p.getDisplayName(),
//                         p.getId().equals(currentParticipantId)
//                 ))
//                 .collect(Collectors.toList());
//     }
   public List<ParticipantDtos.ParticipantRes> getParticipantsForVote(Long voteId, Long currentParticipantId) {
        return participantRepo.findByVoteId(voteId).stream()
                .filter(p -> p.getDisplayName() != null && !p.getDisplayName().isBlank()) // null 필터링도 추가
                .map(p -> new ParticipantDtos.ParticipantRes(
                        p.getId(),
                        p.getDisplayName(),
                        currentParticipantId != null && p.getId().equals(currentParticipantId)
                ))
                .collect(Collectors.toList());
    }

    public ParticipantDtos.ParticipantScheduleRes submitSchedule(
            Long participantId, 
            ParticipantDtos.SubmitScheduleReq request) {
        
        Participant participant = participantRepo.findById(participantId)
                .orElseThrow(() -> new IllegalArgumentException("참가자를 찾을 수 없습니다."));
        
        Vote vote = participant.getVote();
        
        // 1. 기존 데이터 명시적으로 삭제 (Repository 사용) - 수정!
        selectionRepo.deleteByParticipantId(participantId);
        priorityRepo.deleteByParticipantId(participantId);
        selectionRepo.flush();  // DB에 즉시 반영!
        
        // 2. 컬렉션 비우기
        participant.getSelections().clear();
        participant.getPriorities().clear();
        
        // 3. 새로운 선택 저장
        for (ParticipantDtos.DateSlotReq dateSlot : request.schedules()) {
            for (ParticipantDtos.SlotReq slot : dateSlot.slots()) {
                ParticipantSelection selection = ParticipantSelection.builder()
                        .participant(participant)
                        .vote(vote)
                        .date(dateSlot.date())
                        .period(slot.period())
                        .selected(slot.selected())
                        .build();
                participant.getSelections().add(selection);
            }
        }
        
        // 4. 우선순위 저장 (있는 경우)
        if (request.priorities() != null && !request.priorities().isEmpty()) {
            for (ParticipantDtos.PriorityReq priority : request.priorities()) {
                PriorityPreference pref = PriorityPreference.builder()
                        .participant(participant)
                        .vote(vote)
                        .date(priority.date())
                        .period(priority.period())
                        .priorityIndex(priority.priorityIndex())
                        .weight(priority.weight())
                        .createdAt(LocalDateTime.now())
                        .build();
                participant.getPriorities().add(pref);
            }
        }
        
        // 5. 제출 정보 업데이트
        participant.setSubmittedAt(LocalDateTime.now());
        participant.setSubmitted(true);
        
        Participant saved = participantRepo.save(participant);
        participantRepo.flush();
        
        // 6. 응답 생성
        List<ParticipantDtos.SelectionRes> selections = saved.getSelections().stream()
                .map(s -> new ParticipantDtos.SelectionRes(
                    s.getDate(), 
                    s.getPeriod(), 
                    s.isSelected()
                ))
                .toList();
        
        List<ParticipantDtos.PriorityRes> priorities = saved.getPriorities().stream()
                .map(p -> new ParticipantDtos.PriorityRes(
                    p.getDate(), 
                    p.getPeriod(), 
                    p.getPriorityIndex(), 
                    p.getWeight()
                ))
                .toList();
        
        return new ParticipantDtos.ParticipantScheduleRes(
                saved.getId(),
                saved.getDisplayName(),
                selections,
                priorities,
                saved.getSubmittedAt(),
                saved.getSubmitted()
        );
    }
    
    private String genCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i=0;i<len;i++) sb.append(CODE_ALPHABET.charAt(rnd.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /**
     * 특정 참여자의 선택한 일정과 우선순위 조회 (participantId만 사용)
     */
    public ParticipantDtos.ParticipantChoicesRes getParticipantChoices(Long participantId) {

        // 참여자 존재 확인 및 조회
        Participant participant = participantRepo.findById(participantId)
                .orElseThrow(() -> new RuntimeException("Participant not found"));

        // 일정 선택 정보 조회 (엔티티 관계 활용)
        List<ParticipantDtos.SelectionInfo> selectionInfos = participant.getSelections().stream()
                .map(s -> new ParticipantDtos.SelectionInfo(
                        s.getId(),
                        s.getDate().toString(),
                        s.getPeriod(),
                        s.isSelected()
                ))
                .toList();

        // 우선순위 정보 조회 (엔티티 관계 활용)
        List<ParticipantDtos.PriorityInfo> priorityInfos = participant.getPriorities().stream()
                .map(p -> new ParticipantDtos.PriorityInfo(
                        p.getId(),
                        p.getDate().toString(),
                        p.getPeriod(),
                        p.getPriorityIndex(),
                        p.getWeight()
                ))
                .sorted((p1, p2) -> p1.priorityIndex().compareTo(p2.priorityIndex()))
                .toList();

        return new ParticipantDtos.ParticipantChoicesRes(
                participant.getId(),
                participant.getDisplayName(),
                selectionInfos,
                priorityInfos
        );
    }

    public Long getVoteIdByParticipantId(Long participantId) {
        Participant participant = participantRepo.findById(participantId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid participant ID"));
        return participant.getVote().getId();
    }

    public void addIfNotExists(Long voteId, String kakaoId) {
        if (participantRepo.findByVoteIdAndKakaoId(voteId, kakaoId).isEmpty()) {
                Vote v = voteRepo.findById(voteId)
                        .orElseThrow(() -> new NoSuchElementException("vote not found"));
                Participant p = new Participant(v, "미등록", kakaoId); // displayName은 나중에 업데이트
                participantRepo.save(p);
        }
    }

    // 기존 add() 대신, kakaoId로 찾아서 displayName 업데이트
    public ParticipantDtos.ParticipantRes addOrUpdate(Long voteId, String displayName, String kakaoId) {
        Optional<Participant> existing = participantRepo.findByVoteIdAndKakaoId(voteId, kakaoId);
        if (existing.isPresent()) {
                // 이미 등록된 경우 displayName만 업데이트
                Participant p = existing.get();
                p.setDisplayName(displayName);
                participantRepo.save(p);
                return new ParticipantDtos.ParticipantRes(p.getId(), p.getDisplayName(), false);
        }
        // 없으면 새로 등록 (카카오 외 경로)
        return add(voteId, displayName, kakaoId);
    }
}