package com.workingdead.meet.service;

import com.workingdead.meet.dto.ParticipantDtos;
import com.workingdead.meet.dto.VoteDtos;
import com.workingdead.meet.entity.Participant;
import com.workingdead.meet.entity.Vote;
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
        this.voteRepo = voteRepo; this.baseUrl = baseUrl;
    }


    public VoteDtos.VoteSummary create(VoteDtos.CreateVoteReq req){
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
        if (req.name() != null && !req.name().isBlank()) v.setName(req.name());
        if (req.startDate() != null && req.endDate() != null) {
            if (req.endDate().isBefore(req.startDate())) throw new IllegalArgumentException("endDate must be >= startDate");
            v.setDateRange(req.startDate(), req.endDate());
        }
        return toDetail(v);
    }


    public void delete(Long id) {
        voteRepo.deleteById(id);
    }


    private String genCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i=0;i<len;i++) sb.append(CODE_ALPHABET.charAt(rnd.nextInt(CODE_ALPHABET.length())));
// ensure uniqueness (extremely low collision; loop if needed)
        if (voteRepo.findByCode(sb.toString()).isPresent()) return genCode(len);
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
}
