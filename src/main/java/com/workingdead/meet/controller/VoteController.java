package com.workingdead.meet.controller;

import com.workingdead.meet.dto.*;
import com.workingdead.meet.entity.*;
import com.workingdead.meet.repository.*;
import com.workingdead.meet.service.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "Vote", description = "투표 관리 API")
@RestController
@RequestMapping("/votes")
public class VoteController {
        private final VoteService voteService;
        private final ParticipantRepository participantRepository;

        public VoteController(VoteService voteService, ParticipantRepository participantRepository) {
                this.voteService = voteService;
                this.participantRepository = participantRepository;
        }

    @GetMapping("/hi")
    public String hi(){ return "hi";}

    @Operation(
            summary = "투표 목록 조회",
            description = "모든 투표 목록을 조회합니다. 각 투표의 기본 정보(id, name, code, adminUrl, shareUrl, startDate, endDate)를 반환합니다." +
                    "code+baseUrl=shareUrl"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = VoteDtos.VoteSummary.class)))
    })
    // 0.1 홈화면 리스트 & 생성
    @GetMapping
    public List<VoteDtos.VoteSummary> list() { return voteService.listAll(); }

    // 0.2 투표 설정 화면 읽기/수정/삭제
    @GetMapping("/{id}")
    public VoteDtos.VoteDetail get(@PathVariable Long id) { return voteService.get(id); }

    @Operation(
            summary = "공유 코드로 투표 조회",
            description = "공유 URL의 코드를 통해 투표 정보를 조회합니다. 참여자가 투표에 접근할 때 사용됩니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = VoteDtos.VoteDetail.class))),
            @ApiResponse(responseCode = "404", description = "투표를 찾을 수 없음", content = @Content)
    })
    @GetMapping("/share/{code}")
    public ResponseEntity<VoteDtos.VoteDetail> getByShareCode(@PathVariable String code) {
        VoteDtos.VoteDetail vote = voteService.getByCode(code);
        return ResponseEntity.ok(vote);
    }

    @Operation(
            summary = "새 투표 생성",
            description = "새로운 투표를 생성합니다. 고유한 code가 자동 생성되며, shareUrl을 통해 참여자에게 공유할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "생성 성공",
                    content = @Content(schema = @Schema(implementation = VoteDtos.VoteSummary.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (name이 비어있는 경우)", content = @Content)
    })
    @PostMapping
    public ResponseEntity<VoteDtos.VoteSummary> create(@RequestBody @Valid VoteDtos.CreateVoteReq req) {
        var res = voteService.create(req);
// 0.2.3 링크 복사: res.shareUrl 포함
        return ResponseEntity.ok(res);
    }


    @Operation(
            summary = "투표 정보 수정",
            description = "투표의 이름 또는 날짜 범위를 수정합니다. endDate는 startDate보다 크거나 같아야 합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(schema = @Schema(implementation = VoteDtos.VoteDetail.class))),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (endDate < startDate)", content = @Content),
            @ApiResponse(responseCode = "404", description = "투표를 찾을 수 없음", content = @Content)
    })
    @PatchMapping("/{id}")
    public VoteDtos.VoteDetail update(@PathVariable Long id, @RequestBody VoteDtos.UpdateVoteReq req) {
// 날짜 범위 검증은 서비스에서 처리 (end >= start)
        return voteService.update(id, req);
    }

    @Operation(
            summary = "투표 삭제",
            description = "투표를 삭제합니다. 연관된 모든 참여자도 함께 삭제됩니다 (cascade)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "투표를 찾을 수 없음", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        voteService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
