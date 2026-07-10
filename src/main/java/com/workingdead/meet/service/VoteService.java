package com.workingdead.meet.service;

import com.workingdead.meet.dto.ParticipantDtos;
import com.workingdead.meet.dto.VoteDtos;
import com.workingdead.meet.entity.Participant;
import com.workingdead.meet.entity.Vote;
import com.workingdead.enums.VoteStatus;
import com.workingdead.meet.repository.VoteRepository;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

@Service
@Transactional
public class VoteService {

    private final VoteRepository voteRepo;
    private final String baseUrl;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no confusing chars
    private final SecureRandom rnd = new SecureRandom();

    public VoteService(VoteRepository voteRepo, @Value("${app.base-url:http://schedulyy.netlify.app}") String baseUrl) {
        this.voteRepo = voteRepo;
        this.baseUrl = baseUrl;
    }

    public VoteDtos.VoteSummary create(VoteDtos.CreateVoteReq req) {
        //1. Vote c
        String code = genCode(8);
        Vote v = new Vote(req.name(), code);

        // 2. 날짜 범위 설정 (있으면)
        if (req.startDate() != null && req.endDate() != null) {
            if (req.endDate().isBefore(req.startDate())) {
                throw new IllegalArgumentException("endDate must be >= startDate");
            }
            v.setDateRange(req.startDate(), req.endDate());

            // 3. 참여자 추가 (있으면)
            if (req.participantNames() != null && !req.participantNames().isEmpty()) {
                for (String name : req.participantNames()) {
                    if (name != null && !name.isBlank()) {
                        Participant p = new Participant(v, name.trim(), null);
                        v.getParticipants().add(p);
                    }
                }
            }
        }

        voteRepo.save(v);
        return toSummary(v);
    }

    public List<VoteDtos.VoteSummary> listAll() {
        return voteRepo.findAll().stream().map(this::toSummary).toList();
    }

    public VoteDtos.VoteDetail get(Long id) {
        Vote v = voteRepo.findById(id).orElseThrow(() -> new NoSuchElementException("vote not found"));
        return toDetail(v);
    }

    public VoteDtos.VoteDetail getByCode(String code) {
        Vote v = voteRepo.findByCode(code)
                .orElseThrow(() -> new NoSuchElementException("vote not found with code: " + code));
        return toDetail(v);
    }

    public VoteDtos.VoteDetail update(Long id, VoteDtos.UpdateVoteReq req) {
        Vote v = voteRepo.findById(id).orElseThrow(() -> new NoSuchElementException("vote not found"));
        if (req.name() != null && !req.name().isBlank()) {
            v.setName(req.name());
        }
        if (req.startDate() != null && req.endDate() != null) {
            if (req.endDate().isBefore(req.startDate())) {
                throw new IllegalArgumentException("endDate must be >= startDate");
            }
            v.setDateRange(req.startDate(), req.endDate());
        }
        return toDetail(v);
    }

    public void delete(Long id) {
        voteRepo.deleteById(id);
    }

    private String genCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CODE_ALPHABET.charAt(rnd.nextInt(CODE_ALPHABET.length())));
        }
// ensure uniqueness (extremely low collision; loop if needed)
        if (voteRepo.findByCode(sb.toString()).isPresent()) {
            return genCode(len);
        }
        return sb.toString();
    }

    private VoteDtos.VoteSummary toSummary(Vote v) {
        String admin = baseUrl + "/admin/votes/" + v.getId();
        String share = baseUrl + "/v/" + v.getCode();
        return new VoteDtos.VoteSummary(v.getId(), v.getName(), v.getCode(), admin, share, v.getStartDate(), v.getEndDate());
    }

    private VoteDtos.VoteDetail toDetail(Vote v) {
        var participants = v.getParticipants().stream()
                .map(p -> new ParticipantDtos.ParticipantRes(p.getId(), p.getDisplayName(), false))
                .toList();

        return new VoteDtos.VoteDetail(v.getId(), v.getName(), v.getCode(), v.getStartDate(), v.getEndDate(), participants);
    }

    public void updateBotGroupKey(Long voteId, String botGroupKey) {
        Vote v = voteRepo.findById(voteId)
                .orElseThrow(() -> new NoSuchElementException("vote not found"));
        v.setBotGroupKey(botGroupKey);
        voteRepo.save(v);
    }

    /**
     * 투표를 확정 처리한다. 이미 확정된 투표라면 아무것도 하지 않고 false를 반환한다.
     *
     * checkAllVoted / shareVoteStatus / finalizeIfNoResponse 세 경로가 각자 독립적으로
     * "전원 완료"를 감지해서 이 메서드를 부를 수 있는데, 이 반환값으로 "내가 실제로 방금
     * 확정시킨 게 맞는지"를 판단해서, 실제로 상태를 바꾼 호출자만 finish_D 알림을 보내도록
     * 한다. 그래야 여러 경로가 거의 동시에 감지해도 알림이 중복 발송되지 않는다.
     *
     * @return 이번 호출로 ONGOING → FINALIZED 전환이 실제로 일어났으면 true,
     *         이미 FINALIZED라 아무 것도 안 했으면 false
     */
    public boolean finalize(Long voteId) {
        Vote v = voteRepo.findById(voteId)
                .orElseThrow(() -> new NoSuchElementException("vote not found"));

        if (v.getStatus() == VoteStatus.FINALIZED) {
            return false;
        }

        v.setStatus(VoteStatus.FINALIZED);
        voteRepo.save(v);
        return true;
    }
}