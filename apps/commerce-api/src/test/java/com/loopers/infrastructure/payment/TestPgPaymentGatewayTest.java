package com.loopers.infrastructure.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.application.payment.port.PaymentTransactionLookup;
import com.loopers.application.payment.port.PaymentTransactionStatus;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.domain.common.Money;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.payment.TestPgServer.LookupBehavior;
import com.loopers.infrastructure.payment.TestPgServer.RecordedRequest;
import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TestPgPaymentGatewayTest {
    private static final String USER_ID = "135135";
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final String CARD_NO = "1234-5678-9814-1451";
    private static final long AMOUNT = 5_000;
    // read timeout보다 충분히 긴 고정 지연. 테스트 PG가 거래를 만든 뒤 이만큼 늦게 응답한다.
    private static final Duration SERVER_DELAY = Duration.ofMillis(1_500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestPgServer server;
    private TestPgPaymentGateway gateway;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestPgServer(USER_ID, SERVER_DELAY);
        gateway = gatewayFor(server.baseUrl());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        ((Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class)).detachAppender(logs);
        server.close();
    }

    @Nested
    @DisplayName("요청 계약")
    class RequestContract {
        @Test
        @DisplayName("결제 생성은 POST /api/v1/payments로 X-USER-ID와 JSON 본문(숫자 amount, 설정의 callbackUrl)을 보낸다")
        void sendsCreateRequestByContract() throws IOException {
            gateway.create(command());

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.header("Content-Type")).startsWith("application/json");
            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("orderId").isTextual()).isTrue();
            assertThat(body.get("orderId").asText()).isEqualTo(ORDER_ID);
            assertThat(body.get("cardType").asText()).isEqualTo("SAMSUNG");
            assertThat(body.get("cardNo").asText()).isEqualTo(CARD_NO);
            assertThat(body.get("amount").isIntegralNumber()).isTrue();
            assertThat(body.get("amount").asLong()).isEqualTo(AMOUNT);
            assertThat(body.get("callbackUrl").asText()).isEqualTo(CALLBACK_URL);
            assertThat(body.size()).isEqualTo(5);
        }

        @Test
        @DisplayName("거래 키 조회는 GET /api/v1/payments/{transactionKey}로 X-USER-ID를 보내고 키를 경로 값으로 인코딩한다")
        void sendsKeyLookupByContract() {
            gateway.findByTransactionKey("TR/1 2");

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments/TR%2F1%202");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("주문 조회는 GET /api/v1/payments?orderId=로 보내고 특수 문자를 query 값으로 인코딩한다")
        void encodesOrderIdQuery() {
            String orderId = "LP-ORD 42&status=SUCCESS+가";

            gateway.findByOrderId(orderId);

            RecordedRequest request = server.requests().get(0);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.header("X-USER-ID")).isEqualTo(USER_ID);
            assertThat(request.rawQuery()).startsWith("orderId=").doesNotContain("&").doesNotContain("+");
            assertThat(URLDecoder.decode(request.rawQuery().substring("orderId=".length()), StandardCharsets.UTF_8))
                .isEqualTo(orderId);
        }
    }

    @Nested
    @DisplayName("생성 결과 분류")
    class CreateClassification {
        static Stream<Arguments> cases() {
            return Stream.of(
                arguments("202 PENDING → 접수됨", CreateBehavior.ACCEPT_PENDING, PaymentCreateResult.Accepted.class),
                arguments("202 SUCCESS → 접수됨", CreateBehavior.ACCEPT_SUCCESS, PaymentCreateResult.Accepted.class),
                arguments("202 FAILED → 접수됨", CreateBehavior.ACCEPT_FAILED, PaymentCreateResult.Accepted.class),
                arguments("422 계약 거절 → 효과 없는 거절", CreateBehavior.REJECT_BEFORE_CREATE,
                    PaymentCreateResult.RejectedNoEffect.class),
                arguments("200 정상 본문 → 결과 모름", CreateBehavior.ACCEPT_WITH_200, PaymentCreateResult.Unknown.class),
                arguments("202 빈 transactionKey → 결과 모름", CreateBehavior.ACCEPT_BLANK_TRANSACTION_KEY,
                    PaymentCreateResult.Unknown.class),
                arguments("202 알 수 없는 status → 결과 모름", CreateBehavior.ACCEPT_UNKNOWN_STATUS,
                    PaymentCreateResult.Unknown.class),
                arguments("202 금액 불일치 → 결과 모름", CreateBehavior.ACCEPT_AMOUNT_MISMATCH, PaymentCreateResult.Unknown.class),
                arguments("202 orderId 불일치 → 결과 모름", CreateBehavior.ACCEPT_ORDER_ID_MISMATCH,
                    PaymentCreateResult.Unknown.class),
                arguments("202 amount가 문자열 → 결과 모름", CreateBehavior.ACCEPT_AMOUNT_AS_STRING,
                    PaymentCreateResult.Unknown.class),
                arguments("202 깨진 JSON → 결과 모름", CreateBehavior.ACCEPT_BROKEN_JSON, PaymentCreateResult.Unknown.class),
                arguments("본문 없는 422 → 결과 모름", CreateBehavior.REJECT_WITHOUT_BODY, PaymentCreateResult.Unknown.class),
                arguments("422 transactionCreated=true → 결과 모름", CreateBehavior.REJECT_TRANSACTION_CREATED,
                    PaymentCreateResult.Unknown.class),
                arguments("422 다른 code → 결과 모름", CreateBehavior.REJECT_OTHER_CODE, PaymentCreateResult.Unknown.class),
                arguments("422 금액 불일치 → 결과 모름", CreateBehavior.REJECT_AMOUNT_MISMATCH, PaymentCreateResult.Unknown.class),
                arguments("400 → 결과 모름", CreateBehavior.ERROR_400, PaymentCreateResult.Unknown.class),
                arguments("500 거래 생성 후 → 결과 모름", CreateBehavior.ERROR_500_AFTER_CREATE, PaymentCreateResult.Unknown.class),
                arguments("500 거래 생성 없이 → 결과 모름", CreateBehavior.ERROR_500_WITHOUT_CREATE,
                    PaymentCreateResult.Unknown.class),
                arguments("500 + 거절 본문 → 결과 모름", CreateBehavior.ERROR_500_WITH_REJECT_BODY,
                    PaymentCreateResult.Unknown.class),
                arguments("500 + 요청 본문 반사 → 결과 모름", CreateBehavior.ERROR_500_ECHO_REQUEST,
                    PaymentCreateResult.Unknown.class),
                arguments("read timeout(거래는 생성됨) → 결과 모름", CreateBehavior.DELAY_AFTER_CREATE,
                    PaymentCreateResult.Unknown.class),
                arguments("연결 후 끊김(거래는 생성됨) → 결과 모름", CreateBehavior.DROP_CONNECTION_AFTER_CREATE,
                    PaymentCreateResult.Unknown.class)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("PG 응답에 따라 생성 결과를 분류하고 create를 정확히 한 번만 보내며 카드 번호를 남기지 않는다")
        void classifiesCreateResult(String name, CreateBehavior behavior, Class<? extends PaymentCreateResult> expected) {
            server.createBehavior(behavior);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isInstanceOf(expected);
            assertThat(server.createCalls()).isEqualTo(1);
            assertThat(result.toString()).doesNotContain(CARD_NO);
            assertThat(loggedText()).doesNotContain(CARD_NO);
        }

        @Test
        @DisplayName("202 접수 응답이면 PG가 보고한 거래 키·주문 식별자·금액·상태를 그대로 돌려준다")
        void returnsAcceptedTransaction() {
            server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

            PaymentCreateResult result = gateway.create(command());

            String key = server.transactions().get(0).transactionKey();
            assertThat(result).isEqualTo(new PaymentCreateResult.Accepted(
                new PaymentTransaction(key, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.SUCCESS)));
        }

        @Test
        @DisplayName("422 계약 거절이면 PG의 거절 코드를 돌려준다")
        void returnsRejectCode() {
            server.createBehavior(CreateBehavior.REJECT_BEFORE_CREATE);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isEqualTo(new PaymentCreateResult.RejectedNoEffect(TestPgServer.REJECT_CODE));
            assertThat(server.transactions()).isEmpty();
        }

        @Test
        @DisplayName("read timeout이면 테스트 PG에는 거래가 만들어졌어도 결과 모름이며 다시 보내지 않는다")
        void readTimeoutIsUnknownEvenIfCreated() {
            server.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);

            PaymentCreateResult result = gateway.create(command());

            assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class);
            assertThat(server.transactions()).hasSize(1);
            assertThat(server.createCalls()).isEqualTo(1);
        }

        @Test
        @DisplayName("닫힌 포트로 연결이 거부되면 미발송이다")
        void connectionRefusedIsNotSent() throws IOException {
            TestPgPaymentGateway closedPortGateway = gatewayFor(URI.create("http://127.0.0.1:" + closedPort()));

            PaymentCreateResult result = closedPortGateway.create(command());

            assertThat(result).isInstanceOf(PaymentCreateResult.NotSent.class);
            assertThat(result.toString()).doesNotContain(CARD_NO);
            assertThat(loggedText()).doesNotContain(CARD_NO);
        }
    }

    @Nested
    @DisplayName("예외 원인 분류")
    class ExceptionCause {
        static Stream<Arguments> causes() {
            return Stream.of(
                arguments("연결 거부(ConnectException) → 연결 단계", new ConnectException("Connection refused"), true),
                arguments("connect timeout(HttpConnectTimeoutException) → 연결 단계",
                    new HttpConnectTimeoutException("HTTP connect timed out"), true),
                arguments("read timeout(HttpTimeoutException) → 연결 단계 아님", new HttpTimeoutException("request timed out"),
                    false),
                arguments("그 밖의 IOException → 연결 단계 아님", new IOException("connection reset"), false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("causes")
        @DisplayName("HTTP 클라이언트 예외의 원인 타입이 연결 단계 실패일 때만 미발송으로 본다")
        void classifiesByCauseType(String name, IOException cause, boolean expected) {
            ResourceAccessException exception = new ResourceAccessException("I/O error", cause);

            assertThat(TestPgPaymentGateway.failedBeforeSending(exception)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("조회")
    class Lookup {
        @Test
        @DisplayName("거래 키로 조회하면 200 거래를 그대로 돌려준다")
        void findsTransactionByKey() {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

            PaymentTransactionLookup result = gateway.findByTransactionKey(key);

            assertThat(result).isEqualTo(new PaymentTransactionLookup.Found(
                new PaymentTransaction(key, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.SUCCESS)));
        }

        @Test
        @DisplayName("PG가 404로 답하면 거래 없음을 돌려준다")
        void returnsNotFoundFor404() {
            PaymentTransactionLookup result = gateway.findByTransactionKey("TR-UNKNOWN");

            assertThat(result).isInstanceOf(PaymentTransactionLookup.NotFound.class);
        }

        @Test
        @DisplayName("주문 식별자로 조회한 거래가 없으면 빈 목록을 돌려준다")
        void returnsEmptyList() {
            PaymentTransactionsLookup result = gateway.findByOrderId(ORDER_ID);

            assertThat(result).isEqualTo(new PaymentTransactionsLookup.Found(List.of()));
        }

        @Test
        @DisplayName("같은 주문 식별자의 거래가 여러 건이면 모두 돌려준다")
        void returnsAllTransactionsOfOrder() {
            String first = server.addTransaction(ORDER_ID, AMOUNT, "FAILED");
            String second = server.addTransaction(ORDER_ID, AMOUNT, "PENDING");
            server.addTransaction("LP-ORD-0000000099", AMOUNT, "SUCCESS");

            PaymentTransactionsLookup result = gateway.findByOrderId(ORDER_ID);

            assertThat(result).isEqualTo(new PaymentTransactionsLookup.Found(List.of(
                new PaymentTransaction(first, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.FAILED),
                new PaymentTransaction(second, ORDER_ID, new Money(AMOUNT), PaymentTransactionStatus.PENDING))));
        }

        static Stream<Arguments> failures() {
            return Stream.of(
                arguments("키 조회 500", LookupBehavior.ERROR_500, true),
                arguments("키 조회 read timeout", LookupBehavior.DELAY, true),
                arguments("키 조회 깨진 JSON", LookupBehavior.BROKEN_JSON, true),
                arguments("주문 조회 500", LookupBehavior.ERROR_500, false),
                arguments("주문 조회 read timeout", LookupBehavior.DELAY, false),
                arguments("주문 조회 깨진 JSON", LookupBehavior.BROKEN_JSON, false),
                arguments("주문 조회 다른 주문의 거래", LookupBehavior.WRONG_ORDER_ID, false)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("failures")
        @DisplayName("5xx·timeout·해석할 수 없는 응답이면 조회 실패를 돌려준다")
        void returnsFailedForBrokenLookups(String name, LookupBehavior behavior, boolean byKey) {
            String key = server.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");
            server.lookupBehavior(behavior);

            Object result = byKey ? gateway.findByTransactionKey(key) : gateway.findByOrderId(ORDER_ID);

            assertThat(result).isInstanceOfAny(PaymentTransactionLookup.Failed.class, PaymentTransactionsLookup.Failed.class);
            assertThat(server.lookupCalls()).isEqualTo(1);
        }
    }

    private TestPgPaymentGateway gatewayFor(URI baseUrl) {
        return new TestPgPaymentGateway(
            new TestPgProperties(baseUrl, USER_ID, CALLBACK_URL, Duration.ofMillis(300), READ_TIMEOUT), objectMapper);
    }

    private static PaymentCreateCommand command() {
        return new PaymentCreateCommand(ORDER_ID, "SAMSUNG", CARD_NO, new Money(AMOUNT));
    }

    private String loggedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(proxy.getClassName()).append(": ").append(proxy.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
