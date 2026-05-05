package com.workingdead.meet.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.workingdead.enums.Period;
import com.workingdead.meet.dto.VoteDateRangeDtos.DateSlotDto;
import com.workingdead.meet.dto.VoteDateRangeDtos.SlotDto;
import com.workingdead.meet.entity.ParticipantSelection;
import com.workingdead.meet.entity.Vote;
import com.workingdead.meet.repository.ParticipantSelectionRepository;
import com.workingdead.meet.repository.VoteRepository;

@Service
@Transactional(readOnly = true)
public class VoteDateRangeService {

    private final VoteRepository voteRepository;
    private final ParticipantSelectionRepository selectionRepository;

    public VoteDateRangeService(VoteRepository voteRepository,
            ParticipantSelectionRepository selectionRepository) {
        this.voteRepository = voteRepository;
        this.selectionRepository = selectionRepository;
    }

    public List<DateSlotDto> getDateRangeSlots(Long voteId, Long participantId) {
        Vote vote = voteRepository.findById(voteId)
                .orElseThrow(() -> new NoSuchElementException("Vote not found: " + voteId));

        LocalDate start = vote.getStartDate();
        LocalDate end = vote.getEndDate();
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        long days = ChronoUnit.DAYS.between(start, end);
        List<LocalDate> dates = IntStream.rangeClosed(0, (int) days)
                .mapToObj(i -> start.plusDays(i))
                .collect(Collectors.toList());

        List<ParticipantSelection> selections;
        if (participantId == null) {
            selections = selectionRepository.findByVoteId(voteId);
        } else {
            selections = selectionRepository.findByVoteIdAndParticipantId(voteId, participantId);
        }

        Map<String, Boolean> selectionMap = new HashMap<>();
        for (ParticipantSelection s : selections) {
            String key = keyOf(s.getDate(), s.getPeriod());
            selectionMap.put(key, selectionMap.getOrDefault(key, false) || s.isSelected());
        }

        List<DateSlotDto> result = new ArrayList<>();
        for (LocalDate date : dates) {
            List<SlotDto> slots = Arrays.stream(Period.values())
                    .map(period -> {
                        // Period enum을 String으로 변환!
                        boolean selected = selectionMap.getOrDefault(keyOf(date, period.name()), false);
                        return new SlotDto(period.name(), selected);
                    })
                    .collect(Collectors.toList());
            result.add(new DateSlotDto(date, slots));
        }

        return result;
    }

    // String period용 (ParticipantSelection에서 사용)
    private String keyOf(LocalDate date, String period) {
        return date.toString() + "|" + period;
    }
}
