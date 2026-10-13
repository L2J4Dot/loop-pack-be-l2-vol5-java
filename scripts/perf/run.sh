#!/usr/bin/env bash
# 상품 조회 쿼리 성능 측정
#
# 사용법 (저장소 루트에서, docker/infra-compose.yml의 mysql이 떠 있는 상태)
#   scripts/perf/run.sh seed              # 측정용 DB(loopers_perf)에 대량 데이터 생성
#   scripts/perf/run.sh bench baseline    # 측정 결과를 docs/week4/query-performance-raw.md 끝에 섹션으로 추가
#   scripts/perf/run.sh bench after-index # 개선 후 같은 방식으로 측정해 비교
#   scripts/perf/run.sh compare baseline after-index  # 두 측정의 쿼리 결과 스냅샷 비교
#
# 환경 변수
#   PERF_DB      측정용 DB 이름 (기본 loopers_perf, 애플리케이션 local DB와 분리)
#   RUNS         쿼리별 측정 횟수 (기본 10, 별도로 워밍업 1회)
#   TIMEOUT_MS   쿼리 1회 제한 시간 (기본 30000). 넘으면 TIMEOUT으로 기록하고 반복 측정을 건너뛴다
#   SLOW_MS      워밍업이 이 시간(ms)을 넘으면 느린 쿼리로 보고 SLOW_RUNS회만 측정 (기본 10000 / 3)
#   QUERY_TIMEOUTS  쿼리별 제한 시간. 예) "Q6_likes_all=300000 Q7_likes_brand=300000"
#   QUERY_RUNS      쿼리별 측정 횟수. 예) "Q6_likes_all=1"
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
SLOW_MS="${SLOW_MS:-10000}"
SLOW_RUNS="${SLOW_RUNS:-3}"
QUERY_TIMEOUTS="${QUERY_TIMEOUTS:-}"
QUERY_RUNS="${QUERY_RUNS:-}"
RESULTS_DIR="$ROOT_DIR/docs/week4/perf-results"
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

# queries.sql을 "이름<TAB>설명<TAB>스냅샷 형식<TAB>SQL" 줄로 변환
list_queries() {
    awk '
        /^-- @name / { name = substr($0, 10); snap = "list"; next }
        /^-- @desc / { desc = substr($0, 10); next }
        /^-- @snapshot / { snap = substr($0, 14); next }
        /^--/ || /^[[:space:]]*$/ { next }
        { sql = $0; sub(/;[[:space:]]*$/, "", sql); print name "\t" desc "\t" snap "\t" sql }
    ' "$PERF_DIR/queries.sql"
}

# "Q6_likes_all=300000 Q7=..." 형식에서 쿼리 이름의 값을 찾는다. 없으면 기본값
lookup() {
    local table="$1" key="$2" default="$3" pair
    for pair in $table; do
        if [ "${pair%%=*}" = "$key" ]; then
            echo "${pair#*=}"
            return
        fi
    done
    echo "$default"
}

with_timeout_hint() {
    # 맨 앞 SELECT에 실행 시간 제한 힌트를 붙인다
    echo "$1" | sed -E "s/^[[:space:]]*SELECT /SELECT \/*+ MAX_EXECUTION_TIME($2) *\/ /I"
}

# 같은 세션에서 쿼리를 count회 실행하고 SHOW PROFILES까지 출력한다(열 이름 포함).
run_session() {
    local sql="$1" count="$2" script="" i
    script="SET profiling_history_size = 100; SET profiling = 1;"
    for ((i = 0; i < count; i++)); do
        script="$script $sql;"
    done
    script="$script SHOW PROFILES;"
    echo "$script" | mysql_db --force --batch "$PERF_DB" 2>/dev/null
}

# run_session 출력에서 서버 실행 시간(초)만 뽑는다. 클라이언트·docker exec 오버헤드는 포함되지 않는다.
profile_seconds() {
    awk -F'\t' 'NF == 3 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+\.[0-9]+$/ && toupper($3) ~ /^SELECT/ { print $2 }'
}

# run_session 출력에서 첫 결과 집합을 스냅샷 형식으로 바꾼다.
#   list: id, price, created_at (+ likes 형식이면 좋아요 수) / agg: product_id, total
snapshot_rows() {
    local snap="$1"
    awk -F'\t' -v snap="$snap" '
        /^Query_ID\tDuration\tQuery$/ { exit }
        !header_done {
            for (i = 1; i <= NF; i++) { col[$i] = i }
            header_done = 1
            next
        }
        snap == "agg" { print $(col["product_id"]) "\t" $(col["total"]); next }
        { print $(col["id"]) "\t" $(col["price"]) "\t" $(col["created_at"]) }
    '
}

# 목록 스냅샷(id, price, created_at)에 상품별 좋아요 수를 붙인다. 순서는 그대로 유지한다.
append_like_counts() {
    local rows="$1" ids
    ids="$(cut -f1 "$rows" | paste -sd, -)"
    if [ -z "$ids" ]; then
        return
    fi
    echo "SELECT product_id, COUNT(*) FROM product_likes WHERE product_id IN ($ids) GROUP BY product_id;" \
        | mysql_db --batch --skip-column-names "$PERF_DB" \
        | awk -F'\t' 'NR == FNR { likes[$1] = $2; next } { print $0 "\t" (($1 in likes) ? likes[$1] : 0) }' - "$rows"
}

write_snapshot() {
    local file="$1" snap="$2" session="$3" rows
    rows="$(mktemp)"
    snapshot_rows "$snap" < "$session" > "$rows"
    case "$snap" in
        # GROUP BY 결과는 순서가 보장되지 않으므로 product_id로 정렬해 비교한다
        agg) { printf 'product_id\ttotal\n'; sort -n "$rows"; } > "$file" ;;
        list_likes) { printf 'id\tprice\tcreated_at\tlikes\n'; append_like_counts "$rows"; } > "$file" ;;
        *) { printf 'id\tprice\tcreated_at\n'; cat "$rows"; } > "$file" ;;
    esac
    rm -f "$rows"
}

stats_ms() {
    # 입력: 초 단위 값 목록 / 출력: 평균 중앙값 최소 최대 (ms)
    sort -n | awk '{ v[NR] = $1 * 1000; s += v[NR] } END {
        if (NR == 0) { print "-\t-\t-\t-"; exit }
        med = (NR % 2) ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2
        printf "%.2f\t%.2f\t%.2f\t%.2f\n", s / NR, med, v[1], v[NR]
    }'
}

env_line() {
    local os cpu mem java_ver
    if [ "$(uname)" = "Darwin" ]; then
        os="macOS $(sw_vers -productVersion 2>/dev/null) ($(uname -m))"
        cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null), $(sysctl -n hw.ncpu 2>/dev/null)코어"
        mem="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))GB"
    else
        os="$(uname -sr) ($(uname -m))"
        cpu="$(awk -F': ' '/model name/ { print $2; exit }' /proc/cpuinfo 2>/dev/null), $(nproc 2>/dev/null)코어"
        mem="$(awk '/MemTotal/ { printf "%d", $2 / 1024 / 1024 + 0.5 }' /proc/meminfo 2>/dev/null)GB"
    fi
    java_ver="$(java -version 2>&1 | grep -v 'Picked up' | head -1)"
    echo "- 환경: $os / CPU $cpu / 메모리 $mem / MySQL $(echo 'SELECT VERSION();' | mysql_db --batch --skip-column-names) / Java: ${java_ver:-없음}"
}

data_lines() {
    echo -n "- 데이터: "
    echo "SELECT CONCAT('users ', (SELECT COUNT(*) FROM users), ' / brands ', (SELECT COUNT(*) FROM brands),
          ' (삭제 ', (SELECT COUNT(*) FROM brands WHERE deleted = 1), ') / products ', (SELECT COUNT(*) FROM products),
          ' (미삭제 ', (SELECT COUNT(*) FROM products WHERE deleted = 0), ') / product_likes ',
          (SELECT COUNT(*) FROM product_likes));" | mysql_db --batch --skip-column-names "$PERF_DB"
    echo -n "- 인덱스: "
    echo "SELECT GROUP_CONCAT(CONCAT(TABLE_NAME, '.', INDEX_NAME, '(', cols, ')') ORDER BY TABLE_NAME, INDEX_NAME SEPARATOR ', ')
          FROM (SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = '$PERF_DB' AND TABLE_NAME IN ('users', 'brands', 'products', 'product_likes')
                GROUP BY TABLE_NAME, INDEX_NAME) t;" | mysql_db --batch --skip-column-names "$PERF_DB"
}

bench() {
    local label="${1:?측정 이름을 지정하세요. 예: baseline}"
    local doc="$ROOT_DIR/docs/week4/query-performance-raw.md"
    local out_dir="$RESULTS_DIR/$label"
    local tmp_rows tmp_explain session
    mkdir -p "$out_dir"
    tmp_rows="$(mktemp)"
    tmp_explain="$(mktemp)"
    session="$(mktemp)"
    trap 'rm -f "$tmp_rows" "$tmp_explain" "$session"' RETURN

    local name desc snap sql timeout runs hinted warm plan result timed_out
    while IFS=$'\t' read -r name desc snap sql; do
        timeout="$(lookup "$QUERY_TIMEOUTS" "$name" "$TIMEOUT_MS")"
        hinted="$(with_timeout_hint "$sql" "$timeout")"
        echo "[bench] $name - $desc (제한 ${timeout}ms)"

        # 첫 테이블(최상위 SELECT)의 EXPLAIN 한 줄을 요약표에 쓴다
        plan="$(echo "EXPLAIN $sql;" | mysql_db --batch --skip-column-names "$PERF_DB" | head -1 \
            | awk -F'\t' '{ printf "%s\t%s\t%s\t%s", $5, $7, $10, $12 }')"

        # 워밍업 1회: 실행 시간으로 느린 쿼리인지 판단하고, 결과 행을 스냅샷으로 저장한다
        run_session "$hinted" 1 > "$session"
        warm="$(profile_seconds < "$session")"
        timed_out="false"
        if [ -z "$warm" ] || awk -v w="$warm" -v t="$timeout" 'BEGIN { exit !(w * 1000 >= t * 0.99) }'; then
            timed_out="true"
            runs="-"
            result="TIMEOUT(>${timeout})\t-\t-\t-"
            echo "# TIMEOUT (>${timeout}ms) - 결과 없음" > "$out_dir/sql-$name.tsv"
        else
            write_snapshot "$out_dir/sql-$name.tsv" "$snap" "$session"
            runs="$RUNS"
            if awk -v w="$warm" -v s="$SLOW_MS" 'BEGIN { exit !(w * 1000 >= s) }' && [ "$SLOW_RUNS" -lt "$runs" ]; then
                runs="$SLOW_RUNS"
            fi
            runs="$(lookup "$QUERY_RUNS" "$name" "$runs")"
            result="$(run_session "$hinted" "$runs" | profile_seconds | stats_ms)"
        fi

        printf "| %s | %s | %s | %s | %s |\n" "$name" "$desc" "$runs" \
            "$(printf '%b' "$result" | sed 's/\t/ | /g')" "$(echo "$plan" | sed 's/\t/ | /g')" >> "$tmp_rows"

        {
            echo "-- ==================== $name: $desc"
            echo "$sql;"
            echo
            echo "-- EXPLAIN"
            echo "EXPLAIN $sql;" | mysql_db --table "$PERF_DB"
            if [ "$timed_out" = "true" ]; then
                echo "-- EXPLAIN ANALYZE: 생략 (${timeout}ms 안에 끝나지 않음)"
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
        env_line
        echo "- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms)."
        echo "  기본 제한 ${TIMEOUT_MS}ms, 워밍업이 ${SLOW_MS}ms 이상이면 ${SLOW_RUNS}회만 실행. 쿼리별 제한: ${QUERY_TIMEOUTS:-없음}"
        echo "- 결과 스냅샷: \`docs/week4/perf-results/$label/sql-*.tsv\`"
        data_lines
        echo
        echo '| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |'
        echo '| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |'
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
    echo "[bench] 완료: docs/week4/query-performance-raw.md 에 '측정: $label' 섹션 추가, 스냅샷 ${out_dir#$ROOT_DIR/}/"
}

# 두 측정의 결과 스냅샷을 비교한다. 결과가 다른 쿼리·요청은 성능 비교에서 제외해야 한다.
compare() {
    local base="${1:?기준 측정 이름}" target="${2:?비교 측정 이름}"
    local base_dir="$RESULTS_DIR/$base" target_dir="$RESULTS_DIR/$target"
    local file name differ=0 skipped=0 same=0
    [ -d "$base_dir" ] || { echo "스냅샷 없음: ${base_dir#$ROOT_DIR/}" >&2; exit 1; }
    [ -d "$target_dir" ] || { echo "스냅샷 없음: ${target_dir#$ROOT_DIR/}" >&2; exit 1; }

    for file in "$base_dir"/*.tsv; do
        name="$(basename "$file" .tsv)"
        if [ ! -f "$target_dir/$name.tsv" ]; then
            echo "[없음] $name: $target 에 스냅샷이 없음"
            differ=$((differ + 1))
        elif grep -q '^# TIMEOUT' "$file" "$target_dir/$name.tsv"; then
            echo "[비교 불가] $name: 한쪽 이상이 TIMEOUT이라 결과가 없음"
            skipped=$((skipped + 1))
        elif cmp -s "$file" "$target_dir/$name.tsv"; then
            same=$((same + 1))
        else
            echo "[다름] $name (< $base / > $target)"
            diff "$file" "$target_dir/$name.tsv" | grep "^[<>]" | head -20 || true
            differ=$((differ + 1))
        fi
    done
    for file in "$target_dir"/*.tsv; do
        name="$(basename "$file" .tsv)"
        if [ ! -f "$base_dir/$name.tsv" ]; then
            echo "[없음] $name: $base 에 스냅샷이 없음"
            differ=$((differ + 1))
        fi
    done
    echo "[compare] 같음 $same / 다름 $differ / 비교 불가 $skipped"
    [ "$differ" -eq 0 ]
}

case "${1:-}" in
    seed) seed ;;
    bench) shift; bench "$@" ;;
    compare) shift; compare "$@" ;;
    *)
        sed -n '2,/^set -euo/p' "$0" | sed '$d'
        exit 1
        ;;
esac
