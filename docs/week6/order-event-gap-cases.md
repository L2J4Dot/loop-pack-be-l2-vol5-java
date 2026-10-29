# 주문 확정 이벤트 누락 구간 재현 (과제 실험 ④)

주문 DB commit과 Kafka send 사이에는 원자성이 없다. 이 문서는 그 구간에서 이벤트가 빠지는 사례(A)와 정상 사례(B)를 같은 조건으로 재현한 기록이다. 7주차 Outbox 작업은 이 기록에서 출발한다.

## 역할

| 사례 | 역할 |
| --- | --- |
| A (미발행) | 주문은 확정·예약 소비까지 commit됐는데 그 확정의 OrderConfirmed가 broker에 없다. 3b 대사·7주차 Outbox가 메워야 하는 구간 |
| B (정상) | 같은 경로로 확정하고 broker까지 보낸 기준 사례 |

## 재현 조건

- 사용한 커밋: `ecf41f6` (`OrderConfirmedGapCaseTest`), 발행 구현은 `237e8c6`·`408bf6b`
- 실행: `./gradlew :apps:commerce-api:test --tests '*OrderConfirmedGapCaseTest' -i` 후 `[실험④ 사례 A]`·`[실험④ 사례 B]` 줄 확인
- 공통: 고정 Clock `2026-10-29T01:00:00Z`, 상품 2개(재고 각 5), 주문 품목 첫 상품×2·둘째 상품×1. 기존 주문 API(`POST /api/v1/orders/{id}/confirm`) → 로컬 테스트 PG 서버(create 즉시 SUCCESS) → 실제 결과 반영 경로로 확정한다.
- broker: Testcontainers `apache/kafka:3.8.1`, 테스트마다 고유 접미사를 붙인 단일 partition topic. 고유 group의 테스트 consumer로 읽는다.
- 사례 A의 멈춤: 테스트 설정에서 `OrderConfirmedPublisher`를 대역으로 바꿔 끼워, commit 뒤 after-commit listener가 부른 send 직전에 payload만 남기고 보내지 않는다. 운영 코드에는 분기가 없다. 프로세스를 강제 종료한 것이 아니라, commit 뒤 send 전에 멈춘 상황을 흉내 낸 것이다.
- 사례 A의 "없음": 멈춤을 풀고 다른 주문(경계)을 같은 경로로 확정해 그 record가 도착할 때까지 읽은 뒤 판단한다. 한 partition 안에서는 같은 producer가 보낸 순서대로 쌓이므로, 대상 record가 보내졌다면 경계보다 앞에 있어야 한다.

## 결과

카드 번호·PG 비밀은 적지 않는다. 확정 시각은 DB(UTC) 값이다.

| 항목 | 사례 A (미발행) | 사례 B (정상) |
| --- | --- | --- |
| orderId | 1 | 1 |
| providerOrderId | `LP-ORD-0000000001` | `LP-ORD-0000000001` |
| transactionKey | `TR-000001` | `TR-000001` |
| eventId | `order-confirmed:v1:1` | `order-confirmed:v1:1` |
| confirmedAt | `2026-10-29T01:00:00Z` | `2026-10-29T01:00:00Z` |
| items | 상품 1×2, 상품 2×1 | 상품 1×2, 상품 2×1 |
| 주문 | `CONFIRMED` | `CONFIRMED` |
| 결제 기록 결과 / PG 상태 | `SUCCESS` / `SUCCESS` | `SUCCESS` / `SUCCESS` |
| 예약(재고 첫/둘째, 시작 5/5) | 3/4 (소비 유지) | 3/4 (소비 유지) |
| broker | 그 eventId record 없음. 경계까지 읽은 eventId는 `order-confirmed:v1:2`뿐 | (topic=`order-events-it-…`, partition=0, offset=0), key=`1` 1건 |

사례 B의 record 값:

```json
{"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:1","orderId":1,"confirmedAt":"2026-10-29T01:00:00Z","items":[{"productId":1,"quantity":2},{"productId":2,"quantity":1}]}
```

테스트마다 테이블을 비워 주문 ID가 1부터 다시 시작하므로 두 사례의 ID가 같다. topic 이름의 접미사는 실행마다 바뀐다.

## 소비 쪽까지의 연결

commerce-api 테스트에서 commerce-streamer를 함께 띄우지 않았다. 대신 공유 계약 fixture(`modules/kafka/src/testFixtures/resources/contracts/order-confirmed-v1.json`)로 연결을 증명한다.

- api `OrderConfirmedContractTest`: 팩토리·직렬화가 같은 주문에서 만든 메시지가 fixture와 정규화해서 같다.
- streamer `OrderConfirmedContractTest`: 같은 fixture를 파싱·처리하면 구매 활동 2행이 남는다.

사례 B의 record는 fixture와 주문 ID·상품 ID만 다른 같은 모양이다. 따라서 streamer는 이 record를 구매 활동 2행으로 처리한다. 사례 A의 확정은 broker에 없으므로 구매 활동에 영영 나타나지 않는다. 대사가 재발행해야만 나타난다.

## 남는 한계

- 사례 A의 확정은 지금은 누구도 다시 보내지 않는다. 실패 지표·ERROR 로그도 남지 않는다. send 실패가 아니라 send가 아예 없었기 때문이다. 3b 대사(`(status, confirmed_at)` 범위 조회 → 같은 payload 생성 코드로 재발행)가 이 구간을 메운다.
- 7주차 Outbox는 확정과 같은 트랜잭션에 발행할 일을 기록해 이 구간 자체를 없애는 방향이다.
