package com.workingdead.meet.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workingdead.meet.dto.PriorityDtos.*;
import com.workingdead.meet.entity.*;
import com.workingdead.meet.repository.*;
//import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import jakarta.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;

import java.time.LocalDateTime;

@Service
public class PriorityService {
    private final PriorityPreferenceRepository prefRepo;
    private final ParticipantRepository participantRepo;
    private final VoteRepository voteRepo;
//    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper om;

    public PriorityService(PriorityPreferenceRepository prefRepo,
                           ParticipantRepository participantRepo,
                           VoteRepository voteRepo,
//                           RedisTemplate<String, String> redisTemplate,
                           ObjectMapper om) {
        this.prefRepo = prefRepo;
        this.participantRepo = participantRepo;
        this.voteRepo = voteRepo;
//        this.redisTemplate = redisTemplate;
        this.om = om;
    }

    private static final Map<Integer, Double> DEFAULT_WEIGHTS = Map.of(
            1, 0.33,
            2, 0.25,
            3, 0.20
    );

    @Transactional
    public PriorityResponse setPriorities(Long participantId,
                                      Long voteId,
                                      PriorityRequest req,
                                      String storage,
                                      boolean dryRun,
                                      HttpSession session) {

    List<PriorityItemReq> items = req.items() == null ? Collections.emptyList() : req.items();
    if (items.size() > 3) throw new IllegalArgumentException("최대 3개의 우선순위만 설정할 수 있습니다.");

    Set<Integer> idx = new HashSet<>();
    for (PriorityItemReq it : items) {
        if (it.priorityIndex() < 1 || it.priorityIndex() > 3)
            throw new IllegalArgumentException("priorityIndex는 1..3만 허용됩니다.");
        if (!idx.add(it.priorityIndex()))
            throw new IllegalArgumentException("priorityIndex는 중복될 수 없습니다.");
        if (it.date() == null) throw new IllegalArgumentException("date는 필수입니다.");
        if (it.period() == null) throw new IllegalArgumentException("period는 필수입니다.");
    }

    var participant = participantRepo.findById(participantId)
            .orElseThrow(() -> new NoSuchElementException("participant not found"));
    var vote = voteRepo.findById(voteId)
            .orElseThrow(() -> new NoSuchElementException("vote not found"));

    List<PriorityPreference> existing = prefRepo.findByParticipantIdAndVoteId(participantId, voteId);

    Map<String, PriorityPreference> existMap = existing.stream()
            .collect(Collectors.toMap(p -> keyOf(p.getDate(), p.getPeriod(), p.getPriorityIndex()), p -> p));

    Map<String, PriorityItemReq> desiredMap = items.stream()
            .collect(Collectors.toMap(it -> keyOf(it.date(), it.period(), it.priorityIndex()), it -> it));

    List<PriorityItemRes> added = new ArrayList<>();
    List<PriorityItemRes> removed = new ArrayList<>();
    List<PriorityItemRes> unchanged = new ArrayList<>();

    // 새로 만들 엔티티만 저장 (기존 것은 그대로 유지)
    List<PriorityPreference> toSave = new ArrayList<>();
    // 삭제할 엔티티만 따로
    List<PriorityPreference> toDelete = new ArrayList<>();

    // Process desired
    for (PriorityItemReq it : items) {
        String period = it.period();
        String key = keyOf(it.date(), period, it.priorityIndex());
        if (existMap.containsKey(key)) {
            // 이미 존재 - 그대로 유지
            PriorityPreference p = existMap.get(key);
            unchanged.add(toRes(p));
        } else {
            // 새로 추가
            double weight = DEFAULT_WEIGHTS.getOrDefault(it.priorityIndex(), 0.0);
            PriorityPreference p = PriorityPreference.builder()
                    .participant(participant)
                    .vote(vote)
                    .date(it.date())
                    .period(period)
                    .priorityIndex(it.priorityIndex())
                    .weight(weight)
                    .createdAt(LocalDateTime.now())
                    .build();
            added.add(new PriorityItemRes(p.getDate(), p.getPeriod(), p.getPriorityIndex(), p.getWeight()));
            toSave.add(p);
        }
    }

    // Process removals
    for (PriorityPreference p : existing) {
        String key = keyOf(p.getDate(), p.getPeriod(), p.getPriorityIndex());
        if (!desiredMap.containsKey(key)) {
            removed.add(toRes(p));
            toDelete.add(p);
        }
    }

    String finalStorage = storage == null ? "db" : storage.toLowerCase();

    if ("db".equals(finalStorage)) {
        // 삭제할 것만 삭제
        if (!toDelete.isEmpty()) {
            prefRepo.deleteAll(toDelete);
            prefRepo.flush();
        }
        // 새로 만든 것만 저장
        if (!toSave.isEmpty()) {
            prefRepo.saveAll(toSave);
        }
    } else if ("session".equals(finalStorage)) {
        // 현재 상태 계산
        List<PriorityPreference> currentPrefs = new ArrayList<>(existing);
        currentPrefs.removeAll(toDelete);
        currentPrefs.addAll(toSave);
        
        try {
            String json = om.writeValueAsString(currentPrefs.stream().map(this::toRes).collect(Collectors.toList()));
            session.setAttribute("participant:" + participantId + ":priorities", json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("session serialization error", e);
        }
//    } else if ("redis".equals(finalStorage)) {
//        if (redisTemplate == null) {
//            throw new IllegalStateException("RedisTemplate bean is not configured");
//        }
//
//        List<PriorityPreference> currentPrefs = new ArrayList<>(existing);
//        currentPrefs.removeAll(toDelete);
//        currentPrefs.addAll(toSave);
//
//        try {
//            String key = "participant:" + participantId + ":priorities";
//            String json = om.writeValueAsString(currentPrefs.stream().map(this::toRes).collect(Collectors.toList()));
//            redisTemplate.opsForValue().set(key, json);
//        } catch (JsonProcessingException e) {
//            throw new RuntimeException("redis serialization error", e);
//        }
    } else {
        throw new IllegalArgumentException("Unknown storage: " + storage);
    }

    if (dryRun) {
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
    }

    List<PriorityItemRes> current;
    if ("db".equals(finalStorage) && !dryRun) {
        current = prefRepo.findByParticipantIdAndVoteId(participantId, voteId).stream()
                .map(this::toRes)
                .collect(Collectors.toList());
    } else {
        // 현재 상태 계산
        List<PriorityPreference> currentPrefs = new ArrayList<>(existing);
        currentPrefs.removeAll(toDelete);
        currentPrefs.addAll(toSave);
        current = currentPrefs.stream().map(this::toRes).collect(Collectors.toList());
    }

    PriorityDiff diff = new PriorityDiff(added, removed, unchanged);
    return new PriorityResponse(finalStorage, dryRun, diff, current);
}


    // Period를 String으로 변경!
    private String keyOf(java.time.LocalDate date, String period, int idx) {
        return date.toString() + "|" + period + "|" + idx;
    }

    private PriorityItemRes toRes(PriorityPreference p) {
        // .name() 제거!
        return new PriorityItemRes(p.getDate(), p.getPeriod(), p.getPriorityIndex(), p.getWeight());
    }
}