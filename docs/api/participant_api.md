# 참여자 관련 APIs

`ParticipantController` (`""`, 경로가 `/votes/{voteId}/participants`와 `/participants/{id}`로 나뉘어 있음) 기준. 
참여자 등록/조회/수정/삭제 및 일정·우선순위 제출을 담당.

## 📝 기본 정보

- Base path: 없음 (컨트롤러 자체는 `@RequestMapping("")`), 경로가 리소스별로 `/votes/{voteId}/participants...` 또는 `/participants/{id}...`로 나뉨
- 요청 방식: GET/POST/PATCH/DELETE 혼합
- 요청 본문: `ParticipantDtos.*`, `PriorityDtos.PriorityRequest`
- 응답 본문: `ParticipantDtos.*`, `PriorityDtos.PriorityResponse`
- 인증: 없음

## ⚙️ APIs
### 1. 참여자 등록/조회/삭제

| Method | Endpoint | 설명 | 비고 |
|---|---|---|---|
| POST | `/votes/{voteId}/participants` | 참여자 추가 | `displayName`/`kakaoId` 기준으로 `addOrUpdate` 호출 — 이미 같은 kakaoId 있으면 이름만 갱신 |
| GET | `/votes/{voteId}/participants/kakao/{kakaoId}` | 카카오 ID로 참여자 조회 | |
| GET | `/votes/{voteId}/participants` | 참여자 목록 조회 (로그인 칩) | `displayName == "미등록"` 또는 빈 값인 참여자는 응답에서 제외 |
| DELETE | `/participants/{participantId}` | 참여자 삭제 | |

### 2. 참여자 정보 수정

| Method | Endpoint | 설명 | 비고 |
|---|---|---|---|
| PATCH | `/participants/{participantId}` | 참여자 기본 정보 수정 | `displayName` 등 |
| PATCH | `/participants/{id}/info` | 참여자 정보 부분 수정 | 리플렉션으로 요청 Map의 key를 `Participant` 필드에 직접 매핑. 필드명이 요청 key와 정확히 일치해야 함 |

### 3. 우선순위 및 일정 제출/조회

| Method | Endpoint | 설명 | 비고 |
|---|---|---|---|
| POST | `/participants/{participantId}?voteId={voteId}` | 우선순위 설정 (최대 3개) | `storage`(기본 `db`), `dryRun`(기본 `false`) 쿼리 파라미터로 동작 제어 |
| PATCH | `/participants/{participantId}/schedule` | 일정 제출 | 제출 성공 시 `KakaoNotifier.shareVoteStatus()`를 동기 호출해 그룹챗에 현황 공유. 알림 실패는 로그만 남기고 응답엔 영향 없음(try-catch) |
| GET | `/participants/{participantId}/choices` | 특정 참여자가 선택한 일정 + 우선순위 조회 |

## 💬 관련 파일

- `ParticipantController.java` — 이 문서가 다루는 컨트롤러
- `ParticipantService.java` — 참여자 CRUD, 일정/우선순위 저장 핵심 로직
- `PriorityService.java` — 우선순위 설정 로직
- `KakaoWendyService.java`, `KakaoNotifier.java` — 일정 제출 시 카카오 그룹챗 알림 연동