# kakao 관련 APIs

`KakaoSkillController` (`/kakao/skill`) 기준. 

## 📝 기본 정보

- Base path: `/kakao/skill`
- 요청 본문: `KakaoRequest` (카카오 SkillRequest 형식)
- 응답 본문: `KakaoResponse` (카카오 SkillResponse 형식, `version: "2.0"`)

## ⚙️ APIs
#### 1. 대화 흐름 (사용자 발화 처리)

| Method | Endpoint | 설명 | 비고 |
|---|---|---|---|
| POST | `/kakao/skill/main` | 폴백 블록. 어떤 블록에도 안 걸린 발화가 전부 여기로 옴 | "참여"/"참여할래요" 처리, 멘션 단독 호출 시 도움말 라우팅, 6개 명령어(참여/시작/도움말/종료/결과/재투표) 처리 |
| POST | `/kakao/skill/start` | 세션 시작 | 전용 블록 연결. 실제 "시작" 발화는 이 엔드포인트로 들어옴 |
| POST | `/kakao/skill/participants` | 참여 등록 | `/main`의 "참여" 분기와 동일 로직(`joinPendingSession`) 호출. 참여 전용 블록을 따로 만들 경우 사용 |
| POST | `/kakao/skill/end` | 세션 종료 | |
| POST | `/kakao/skill/select-week` | 주차 선택 | 전용 블록 연결. `weeks` 파라미터 또는 발화("이번"/"1주" 등)로 파싱. 즉시 Vote 생성 대신 `startCollecting()`으로 24시간 수집 시작 |
| POST | `/kakao/skill/result` | 투표 결과 조회 | |
| POST | `/kakao/skill/revote` | 재투표 | 기존 투표/수집 세션 정리 후 세션 재시작 |
| POST | `/kakao/skill/help` | 도움말 | "아래 버튼을 눌러서 확인하세요" + `action:guide` 버튼 (실제 명령어 목록 문구는 관리자센터의 "챗봇 도움말" 설정에서 관리) |

#### 2. 알림 (Event API 콜백)

카카오 Event API(`sendEventMessage`)로 이벤트를 발송하면, 관리자센터에 등록된 블록을 거쳐 아래 스킬들이 호출되어 실제 메시지 내용을 만듦.

| Method | Endpoint | 설명 | 대응하는 이벤트 이름 |
|---|---|---|---|
| POST | `/kakao/skill/notify/remind` | 미투표자 독촉 알림 (30분/2시간/6시간/12시간 단계별) | `remind_D_30M`, `remind_D_2H`, `remind_D_6H`, `remind_D_12H` |
| POST | `/kakao/skill/notify/vote-created` | 24시간 참여자 수집 완료 → 투표 생성 알림 | `vote_created_D` |
| POST | `/kakao/skill/notify/final-notice` | 최후통첩 (투표 생성 +24시간) | `final_D` |
| POST | `/kakao/skill/notify/final` | 완료 처리 (최후통첩 +25시간, 무응답 시 강제 확정) | `finish_D` |

> 이 4개는 **관리자센터에 이벤트 이름이 정확히 등록되어 있어야** 동작함. 코드에서 `sendEventMessage(botGroupKey, "이벤트이름")`을 아무리 정확히 호출해도, 관리자센터의 해당 블록 + 이벤트 설정이 없으면 카카오가 `404 Invalid Event name`으로 거부함.

#### 3. 시간 투표 (TimePoll)

날짜 투표 확정 이후, 구체적인 시간을 정하는 2차 투표 흐름.

| Method | Endpoint | 설명 |
|---|---|---|
| POST | `/kakao/skill/time-poll` | 시간 투표 생성 (1순위 날짜 기준으로 TimePoll 생성 + 스케줄러 시작) |
| POST | `/kakao/skill/time-poll/notify/remind` | 시간 투표 미투표자 독촉 |
| POST | `/kakao/skill/time-poll/notify/final-notice` | 시간 투표 최후통첩 |
| POST | `/kakao/skill/time-poll/notify/status` | 시간 투표 현황 조회 |
| POST | `/kakao/skill/time-poll/notify/finish` | 시간 투표 완료 처리 (확정 시간 안내) |
| POST | `/kakao/skill/time-poll/notify/final-buttons` | 확정 여부 선택 버튼 응답 ("저도 그때 좋아요" / "투표할래요") |

### 4. 기타

| Method | Endpoint | 설명 |
|---|---|---|
| GET | `/kakao/skill/health` | 헬스체크. 카카오 스킬 서버 상태 확인용, 인증 없음 |

## 🔑 세션 키 규칙

모든 엔드포인트는 `getSessionKey(request)`로 세션 키를 결정:
- 그룹챗이면 `botGroupKey`
- 개인챗이면 `userKey`

## 💬 관련 파일

- `KakaoSkillController.java` — 이 문서가 다루는 컨트롤러
- `KakaoWendyService.java` — 대화 흐름 핵심 로직 (세션/투표/참여자 수집)
- `KakaoWendyScheduler.java` — 리마인더 + 24시간 수집창 스케줄러
- `KakaoNotifier.java` — Event API로 그룹챗에 능동 메시지 발송
- `KakaoTimePollScheduler.java` — 시간 투표용 스케줄러 (별도)