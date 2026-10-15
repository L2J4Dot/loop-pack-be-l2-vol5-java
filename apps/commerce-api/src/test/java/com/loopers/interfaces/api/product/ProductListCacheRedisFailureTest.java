package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이 테스트만 쓰는 Redis 컨테이너를 따로 띄워 멈춘다. 공용 static 컨테이너는 건드리지 않고 FLUSHALL도 하지 않는다.
// docker pause는 연결을 유지한 채 응답만 멈추므로, 명령 타임아웃(1초) 뒤 DB로 응답하는 경로를 확인할 수 있다.
@SpringBootTest
@AutoConfigureMockMvc
class ProductListCacheRedisFailureTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
        .withExposedPorts(6379);
    // 명령 타임아웃 1초 기준으로 Redis 호출이 몇 번 겹쳐도 넉넉한 상한. Lettuce 기본값(60초)이면 넘는다.
    private static final long MAX_DEGRADED_MILLIS = 8000;

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private UserJpaRepository users;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private long productId;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @BeforeEach
    void prepare() {
        users.save(new UserJpaEntity(1));
        long brandId = brandApplicationService.create("브랜드").id().value();
        productId = productApplicationService.create(brandId, "상품", 1000, 3).id();
    }

    @AfterEach
    void clean() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("Redis가 응답하지 않아도 상품 변경은 성공하고 목록은 타임아웃 뒤 DB 값으로 200 응답한다")
    void fallsBackToDatabaseWhenRedisHangs() throws Exception {
        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("상품"));

        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            long started = System.currentTimeMillis();
            productApplicationService.change(productId, "바뀐 상품", 500);
            mvc.perform(get("/api/v1/products").header("X-USER-ID", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(productId))
                .andExpect(jsonPath("$.data[0].name").value("바뀐 상품"))
                .andExpect(jsonPath("$.data[0].price").value(500))
                .andExpect(jsonPath("$.data[0].stock").value(3));
            assertThat(System.currentTimeMillis() - started).isLessThan(MAX_DEGRADED_MILLIS);
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        mvc.perform(get("/api/v1/products").header("X-USER-ID", "1")).andExpect(status().isOk());
    }
}
