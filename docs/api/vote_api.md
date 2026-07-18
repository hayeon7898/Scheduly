# vote 관련 APIs

- `VoteController` (`/votes`) 기준. 
- 투표(Vote) 생성/조회/수정/삭제를 담당.

## 📝 기본 정보

- Base path: `/votes`
- 요청 본문: `VoteDtos.CreateVoteReq`, `VoteDtos.UpdateVoteReq`
- 응답 본문: `VoteDtos.VoteSummary`, `VoteDtos.VoteDetail`, `VoteResultDtos.VoteResultRes`
- 인증: 없음

## ⚙️ APIs

| Method | Endpoint | 설명 | 비고 |
|---|---|---|---|
| GET | `/votes` | 투표 목록 조회 | 전체 투표의 기본 정보(id, name, code, adminUrl, shareUrl, startDate, endDate) 반환 |
| GET | `/votes/{id}` | 투표 상세 조회 | |
| GET | `/votes/share/{code}` | 공유 코드로 투표 조회 | 참여자가 공유 URL(`shareUrl`)로 접근할 때 사용 |
| GET | `/votes/hi` | 헬스체크성 테스트 엔드포인트 | `"hi"` 문자열만 반환. 정식 헬스체크는 아님 |
| GET | `/votes/{voteId}/result` | 투표 결과 조회 | `VoteResultController` 소속. 상위 3개 결과 반환. 정렬 기준: 최다 인원 > 우선순위 가중치 > 빠른 날짜 |
| POST | `/votes` | 새 투표 생성 | 고유 `code` 자동 생성, `shareUrl`(`code`+`baseUrl`) 포함해서 반환 |
| PATCH | `/votes/{id}` | 투표 정보 수정 | 이름 또는 날짜범위 수정. `endDate < startDate`면 400 |
| DELETE | `/votes/{id}` | 투표 삭제 | 연관된 참여자도 cascade로 함께 삭제 |

## 💬 관련 파일

- `VoteController.java` — 목록/조회/생성/수정/삭제 담당
- `VoteResultController.java` — 결과 조회(`/votes/{voteId}/result`) 담당, 컨트롤러는 분리되어 있지만 경로가 같아 이 문서에서 함께 관리
- `VoteService.java` — 투표 생성/조회/수정/삭제, 확정(`finalize`) 로직
- `VoteResultService.java` — 순위 집계 로직 (참여자 선택 + 우선순위 가중치 기반)
- `KakaoWendyService.java` — 카카오 챗봇의 `getVoteResult()`가 이 결과를 텍스트로 가공해 채팅방에 공유