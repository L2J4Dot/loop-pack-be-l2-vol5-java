#!/usr/bin/env bash
# 상품 조회 쿼리 성능 측정
#
# 사용법 (저장소 루트에서, docker/infra-compose.yml의 mysql이 떠 있는 상태)
#   scripts/perf/run.sh seed              # 측정용 DB(loopers_perf)에 대량 데이터 생성
#   scripts/perf/run.sh bench baseline    # 측정 결과를 docs/week4/query-performance-raw.md 끝에 섹션으로 추가
#   scripts/perf/run.sh bench after-index # 개선 후 같은 방식으로 측정해 비교
#
# 환경 변수
#   PERF_DB      측정용 DB 이름 (기본 loopers_perf, 애플리케이션 local DB와 분리)
#   RUNS         쿼리별 측정 횟수 (기본 10, 별도로 워밍업 1회)
#   TIMEOUT_MS   쿼리 1회 제한 시간 (기본 30000). 넘으면 TIMEOUT으로 기록하고 반복 측정을 건너뛴다
#   MYSQL_CMD    mysql 클라이언트 명령 (기본: compose의 mysql 컨테이너에서 root로 실행)
#   seed 규모    BRANDS(200) PRODUCTS(100000) LIKES(1000000) USERS(50000)
#   seed 부하    CHUNK(100000, 청크마다 커밋) CHUNK_SLEEP(0, 청크 사이 쉬는 초)
#   예) PRODUCTS=10000000 LIKES=10000000 CHUNK_SLEEP=0.2 scripts/perf/run.sh seed
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PERF_DIR="$ROOT_DIR/scripts/perf"
PERF_DB="${PERF_DB:-loopers_perf}"
RUNS="${RUNS:-10}"
TIMEOUT_MS="${TIMEOUT_MS:-30000}"
BRANDS="${BRANDS:-200}"
PRODUCTS="${PRODUCTS:-100000}"
LIKES="${LIKES:-1000000}"
USERS="${USERS:-50000}"
CHUNK="${CHUNK:-100000}"
CHUNK_SLEEP="${CHUNK_SLEEP:-0}"
MYSQL_CMD="${MYSQL_CMD:-docker compose -f $ROOT_DIR/docker/infra-compose.yml exec -T mysql mysql -uroot -proot}"

mysql_db() {
    # 비밀번호 경고만 숨기고 나머지 오류는 그대로 보여준다
    $MYSQL_CMD --default-character-set=utf8mb4 "$@" 2> >(grep -v "Using a password" >&2)
}

seed() {
    echo "[seed] $PERF_DB: 브랜드 $BRANDS / 상품 $PRODUCTS / 좋아요 시도 $LIKES (청크 $CHUNK, 쉬는 시간 ${CHUNK_SLEEP}초)"
    echo "CREATE DATABASE IF NOT EXISTS \`$PERF_DB\` DEFAULT CHARACTER SET utf8mb4;" | mysql_db
    {
        echo "SET @perf_brands = $BRANDS, @perf_products = $PRODUCTS, @perf_likes = $LIKES,"
        echo "    @perf_users = $USERS, @perf_chunk = $CHUNK, @perf_sleep = $CHUNK_SLEEP;"
        cat "$PERF_DIR/seed.sql"
    } | mysql_db --batch --skip-column-names "$PERF_DB"
}

# queries.sql을 "이름<TAB>설명<TAB>SQL" 줄로 변환
list_queries() {
    awk '
        /^-- @name / { name = substr($0, 10); next }
        /^-- @desc / { desc = substr($0, 10); next }
        /^--/ || /^[[:space:]]*$/ { next }
        { sql = $0; sub(/;[[:space:]]*$/, "", sql); print name "\t" desc "\t" sql }
    ' "$PERF_DIR/queries.sql"
}

with_timeout_hint() {
    # 맨 앞 SELECT에 실행 시간 제한 힌트를 붙인다
    echo "$1" | sed -E "s/^[[:space:]]*SELECT /SELECT \/*+ MAX_EXECUTION_TIME($TIMEOUT_MS) *\/ /I"
}

# 서버 쪽 실행 시간(초)을 SHOW PROFILES로 얻는다. 클라이언트·docker exec 오버헤드는 포함되지 않는다.
measure() {
    local sql="$1" count="$2" script="" i
    script="SET profiling_history_size = 100; SET profiling = 1;"
    for ((i = 0; i < count; i++)); do
        script="$script $sql;"
    done
    script="$script SHOW PROFILES;"
    echo "$script" | mysql_db --force --batch --skip-column-names "$PERF_DB" 2>/dev/null \
        | awk -F'\t' 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+\.[0-9]+$/ && toupper($3) ~ /^SELECT/ { print $2 }'
}

stats_ms() {
    # 입력: 초 단위 값 목록 / 출력: 평균 중앙값 최소 최대 (ms)
    sort -n | awk '{ v[NR] = $1 * 1000; s += v[NR] } END {
        if (NR == 0) { print "-\t-\t-\t-"; exit }
        med = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
        printf "%.2f\t%.2f\t%.2f\t%.2f\n", s / NR, med, v[1], v[NR]
    }'
}

bench() {
    local label="${1:?측정 이름을 지정하세요. 예: baseline}"
    local doc="$ROOT_DIR/docs/week4/query-performance-raw.md"
    local tmp_rows tmp_explain
    tmp_rows="$(mktemp)"
    tmp_explain="$(mktemp)"
    trap 'rm -f "$tmp_rows" "$tmp_explain"' RETURN

    local name desc sql hinted warm plan result timed_out
    while IFS=$'\t' read -r name desc sql; do
        echo "[bench] $name - $desc"
        hinted="$(with_timeout_hint "$sql")"

        # 첫 테이블(최상위 SELECT)의 EXPLAIN 한 줄을 요약표에 쓴다
        plan="$(echo "EXPLAIN $sql;" | mysql_db --batch --skip-column-names "$PERF_DB" | head -1 \
            | awk -F'\t' '{ printf "%s\t%s\t%s\t%s", $5, $7, $10, $12 }')"

        warm="$(measure "$hinted" 1)"
        timed_out="false"
        if [ -z "$warm" ] || awk -v w="$warm" -v t="$TIMEOUT_MS" 'BEGIN { exit !(w * 1000 >= t * 0.99) }'; then
            timed_out="true"
            result="TIMEOUT(>${TIMEOUT_MS})\t-\t-\t-"
        else
            result="$(measure "$hinted" "$RUNS" | stats_ms)"
        fi

        printf "| %s | %s | %s | %s |\n" "$name" "$desc" \
            "$(printf '%b' "$result" | sed 's/\t/ | /g')" "$(echo "$plan" | sed 's/\t/ | /g')" >> "$tmp_rows"

        {
            echo "-- ==================== $name: $desc"
            echo "$sql;"
            echo
            echo "-- EXPLAIN"
            echo "EXPLAIN $sql;" | mysql_db --table "$PERF_DB"
            if [ "$timed_out" = "true" ]; then
                echo "-- EXPLAIN ANALYZE: 생략 (${TIMEOUT_MS}ms 안에 끝나지 않음)"
            else
                echo "-- EXPLAIN ANALYZE"
                echo "EXPLAIN ANALYZE $sql;" | mysql_db --batch --raw --skip-column-names "$PERF_DB"
            fi
            echo
        } >> "$tmp_explain"
    done < <(list_queries)

    {
        echo
        echo "## 측정: $label ($(date '+%Y-%m-%d %H:%M'))"
        echo
        echo "- 환경: MySQL $(echo 'SELECT VERSION();' | mysql_db --batch --skip-column-names), DB \`$PERF_DB\`"
        echo "- 방식: 쿼리별 워밍업 1회 후 ${RUNS}회 실행, SHOW PROFILES의 서버 실행 시간(ms). 1회 제한 ${TIMEOUT_MS}ms"
        echo -n "- 데이터: "
        echo "SELECT CONCAT('brands ', (SELECT COUNT(*) FROM brands), ' / products ', (SELECT COUNT(*) FROM products),
              ' (미삭제 ', (SELECT COUNT(*) FROM products WHERE deleted = false), ') / product_likes ',
              (SELECT COUNT(*) FROM product_likes));" | mysql_db --batch --skip-column-names "$PERF_DB"
        echo -n "- 인덱스: "
        echo "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '.', INDEX_NAME, '(', cols, ')') ORDER BY TABLE_NAME, INDEX_NAME SEPARATOR ', ')
              FROM (SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                    FROM information_schema.STATISTICS
                    WHERE TABLE_SCHEMA = '$PERF_DB' AND TABLE_NAME IN ('brands', 'products', 'product_likes')
                    GROUP BY TABLE_NAME, INDEX_NAME) t;" | mysql_db --batch --skip-column-names "$PERF_DB"
        echo
        echo '| 쿼리 | 설명 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |'
        echo '| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |'
        cat "$tmp_rows"
        echo
        echo '<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>'
        echo
        echo '```text'
        cat "$tmp_explain"
        echo '```'
        echo
        echo '</details>'
    } >> "$doc"

    echo
    echo "[bench] 완료: docs/week4/query-performance-raw.md 에 '측정: $label' 섹션 추가"
}

case "${1:-}" in
    seed) seed ;;
    bench) shift; bench "$@" ;;
    *)
        sed -n '2,19p' "$0"
        exit 1
        ;;
esac
