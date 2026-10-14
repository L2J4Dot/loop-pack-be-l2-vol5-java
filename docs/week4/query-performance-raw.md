# 쿼리 성능 측정 원본 기록

> `scripts/perf/run.sh bench`가 측정할 때마다 아래에 섹션을 추가한다. 해석과 요약은 [query-performance.md](query-performance.md)에 정리한다.

## 측정: baseline-100k (2026-10-14 01:41)

- 환경: MySQL 8.0.46-0ubuntu0.24.04.4, DB `loopers_perf`
- 방식: 쿼리별 워밍업 1회 후 5회 실행, SHOW PROFILES의 서버 실행 시간(ms). 1회 제한 30000ms
- 데이터: brands 200 / products 100000 (미삭제 94946) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id)

| 쿼리 | 설명 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 45.30 | 45.20 | 43.08 | 46.77 | ALL | NULL | 99806 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 35.06 | 32.16 | 28.86 | 47.87 | ALL | NULL | 99806 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 43.40 | 44.10 | 38.95 | 46.75 | ALL | NULL | 99806 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 32.59 | 32.17 | 31.37 | 35.31 | ALL | NULL | 99806 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 72.88 | 72.66 | 70.46 | 75.86 | ALL | NULL | 99806 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 25176.80 | 25123.85 | 24724.44 | 25798.18 | ALL | NULL | 99806 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 148.23 | 149.84 | 136.05 | 163.60 | index | uk_product_likes_user_product | 970937 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 100.56 | 99.58 | 98.56 | 105.02 | index | uk_product_likes_user_product | 970937 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=68.4..68.4 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=68.4..68.4 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.562..48.8 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.559..39.8 rows=100000 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=39.6..39.6 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=39.6..39.6 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.0948..38.5 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0923..31.7 rows=100000 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=53.7..53.7 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=53.7..53.7 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.165..41.2 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.141..33.2 rows=100000 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=40.9..40.9 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=40.9..40.9 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.105..39.7 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.103..32.2 rows=100000 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=10101 rows=20) (actual time=83.9..83.9 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=10101 rows=99806) (actual time=77.1..81.8 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.0951..37.5 rows=94946 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0931..29.5 rows=100000 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99806 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=41865..41865 rows=20 loops=1)
    -> Sort: (select #2) DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=41865..41865 rows=20 loops=1)
        -> Filter: ((p.brand_id = 200) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.309..39.2 rows=232 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.1..32.7 rows=100000 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Aggregate: count(0)  (cost=20100 rows=1) (actual time=180..180 rows=1 loops=232)
        -> Filter: (l.product_id = p.id)  (cost=10391 rows=97094) (actual time=44..180 rows=8.18 loops=232)
            -> Covering index scan on l using uk_product_likes_user_product  (cost=10391 rows=970937) (actual time=0.499..127 rows=974368 loops=232)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 970937 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=211..211 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=211..211 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=97775 rows=485468) (actual time=0.636..192 rows=94400 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=97775 rows=970937) (actual time=0.635..136 rows=974368 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 970937 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=107484 rows=985) (actual time=168..168 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=97775 rows=97094) (actual time=0.459..167 rows=34627 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=97775 rows=970937) (actual time=0.458..118 rows=974368 loops=1)


```

</details>

## 측정: baseline-10m (2026-10-14 01:23)

- 환경: MySQL 8.0.46-0ubuntu0.24.04.4, DB `loopers_perf`
- 방식: 쿼리별 워밍업 1회 후 5회 실행, SHOW PROFILES의 서버 실행 시간(ms). 1회 제한 30000ms
- 데이터: brands 200 / products 10000000 (미삭제 9500504) / product_likes 9969594
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id)

| 쿼리 | 설명 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 4510.28 | 4499.81 | 4445.54 | 4612.40 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 3466.50 | 3444.84 | 3437.42 | 3543.18 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 4045.59 | 4017.54 | 3919.30 | 4186.31 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 3424.80 | 3459.85 | 3297.99 | 3533.49 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 7600.35 | 7402.65 | 7159.63 | 8286.84 | ALL | NULL | 9960468 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | TIMEOUT(>30000) | - | - | - | ALL | NULL | 9960468 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 1526.14 | 1521.20 | 1469.54 | 1587.04 | index | uk_product_likes_user_product | 9752871 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 1317.86 | 1307.66 | 1286.84 | 1384.89 | index | uk_product_likes_user_product | 9752871 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=5633..5633 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=5633..5633 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.131..4215 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.129..3363 rows=10e+6 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=4543..4543 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=4543..4543 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.04e+6 rows=9.96e+6) (actual time=0.249..4415 rows=670748 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.244..3719 rows=10e+6 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=5237..5237 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=5237..5237 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.1..4029 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.0986..3213 rows=10e+6 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=1.04e+6 rows=20) (actual time=4068..4068 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=4068..4068 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=1.04e+6 rows=9.96e+6) (actual time=0.11..3966 rows=670748 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.108..3322 rows=10e+6 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows    | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=1.04e+6 rows=20) (actual time=8741..8741 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=1.04e+6 rows=9.96e+6) (actual time=8735..8739 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=1.04e+6 rows=9.96e+6) (actual time=0.181..4290 rows=9.5e+6 loops=1)
            -> Table scan on p  (cost=1.04e+6 rows=9.96e+6) (actual time=0.179..3441 rows=10e+6 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows    | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL | 9960468 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+---------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (30000ms 안에 끝나지 않음)

-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9752871 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=2300..2300 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=2300..2300 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=1e+6 rows=4.88e+6) (actual time=0.84..2219 rows=346225 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=1e+6 rows=9.75e+6) (actual time=0.838..1656 rows=9.97e+6 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows    | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 9752871 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+---------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=1.1e+6 rows=3123) (actual time=2226..2226 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=1e+6 rows=975287) (actual time=0.608..2217 rows=151236 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=1e+6 rows=9.75e+6) (actual time=0.607..1639 rows=9.97e+6 loops=1)


```

</details>

## 측정: baseline (2026-10-14 09:25)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 방식: 쿼리별 워밍업 1회 후 '실행 횟수'만큼 실행, SHOW PROFILES의 서버 실행 시간(ms).
  기본 제한 30000ms, 워밍업이 10000ms 이상이면 3회만 실행. 쿼리별 제한: Q6_likes_all=60000 Q7_likes_brand=60000
- 결과 스냅샷: `docs/week4/perf-results/baseline/sql-*.tsv`
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 쿼리 | 설명 | 실행 횟수 | 평균 | 중앙값 | 최소 | 최대 | type | key | rows(예상) | Extra |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |
| Q1_latest_all | 전체 상품 최신순 1페이지 | 10 | 28.20 | 29.39 | 22.93 | 30.21 | ALL | NULL | 99806 | Using where; Using filesort |
| Q2_latest_brand | 큰 브랜드(1) 최신순 1페이지 | 10 | 23.13 | 23.47 | 18.92 | 27.94 | ALL | NULL | 99806 | Using where; Using filesort |
| Q3_price_all | 전체 상품 가격 낮은순 1페이지 | 10 | 22.87 | 22.77 | 22.47 | 23.79 | ALL | NULL | 99806 | Using where; Using filesort |
| Q4_price_brand | 큰 브랜드(1) 가격 낮은순 1페이지 | 10 | 23.67 | 23.57 | 22.28 | 24.87 | ALL | NULL | 99806 | Using where; Using filesort |
| Q5_latest_deep_page | 전체 최신순 깊은 페이지 (page=2500, size=20) | 10 | 58.87 | 57.08 | 55.71 | 66.79 | ALL | NULL | 99806 | Using where; Using filesort |
| Q6_likes_all | 전체 상품 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 99806 | Using where; Using filesort |
| Q7_likes_brand | 큰 브랜드(1) 좋아요순 1페이지 | - | TIMEOUT(>60000) | - | - | - | ALL | NULL | 99873 | Using where; Using filesort |
| Q8_likes_small_brand | 작은 브랜드(200) 좋아요순 1페이지 | 3 | 19212.88 | 19200.95 | 18732.10 | 19705.60 | ALL | NULL | 99873 | Using where; Using filesort |
| Q9_like_count_page | 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds) | 10 | 102.79 | 97.38 | 89.21 | 124.65 | index | uk_product_likes_user_product | 976305 | Using where; Using index; Using temporary |
| Q10_like_count_detail | 상품 상세의 좋아요 수 (인기 상품 1) | 10 | 85.97 | 86.78 | 73.41 | 92.82 | index | uk_product_likes_user_product | 976305 | Using where; Using index |

<details><summary>실행 계획 상세 (EXPLAIN / EXPLAIN ANALYZE)</summary>

```text
-- ==================== Q1_latest_all: 전체 상품 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=33.3..33.3 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=33.3..33.3 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.0942..24.4 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.0926..18.9 rows=100000 loops=1)


-- ==================== Q2_latest_brand: 큰 브랜드(1) 최신순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=31.8..31.8 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=31.8..31.8 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.229..30.7 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.226..24.7 rows=100000 loops=1)


-- ==================== Q3_price_all: 전체 상품 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=42.2..42.2 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=42.2..42.2 rows=20 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.157..32.5 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.154..25.5 rows=100000 loops=1)


-- ==================== Q4_price_brand: 큰 브랜드(1) 가격 낮은순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY p.price asc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |     5.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10101 rows=20) (actual time=22.8..22.8 rows=20 loops=1)
    -> Sort: p.price, p.id DESC, limit input to 20 row(s) per chunk  (cost=10101 rows=99806) (actual time=22.8..22.8 rows=20 loops=1)
        -> Filter: ((p.brand_id = 1) and (p.deleted = false))  (cost=10101 rows=99806) (actual time=0.129..22.2 rows=6694 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.126..18 rows=100000 loops=1)


-- ==================== Q5_latest_deep_page: 전체 최신순 깊은 페이지 (page=2500, size=20)
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY p.created_at desc, p.id desc LIMIT 20 OFFSET 50000;

-- EXPLAIN
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
| id | select_type | table | partitions | type | possible_keys | key  | key_len | ref  | rows  | filtered | Extra                       |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
|  1 | SIMPLE      | p     | NULL       | ALL  | NULL          | NULL | NULL    | NULL | 99806 |    50.00 | Using where; Using filesort |
+----+-------------+-------+------------+------+---------------+------+---------+------+-------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit/Offset: 20/50000 row(s)  (cost=10101 rows=20) (actual time=70.2..70.2 rows=20 loops=1)
    -> Sort: p.created_at DESC, p.id DESC, limit input to 50020 row(s) per chunk  (cost=10101 rows=99806) (actual time=65.6..68.5 rows=50020 loops=1)
        -> Filter: (p.deleted = false)  (cost=10101 rows=99806) (actual time=0.107..32.7 rows=92859 loops=1)
            -> Table scan on p  (cost=10101 rows=99806) (actual time=0.105..23.4 rows=100000 loops=1)


-- ==================== Q6_likes_all: 전체 상품 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |    50.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q7_likes_brand: 큰 브랜드(1) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=1 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE: 생략 (60000ms 안에 끝나지 않음)

-- ==================== Q8_likes_small_brand: 작은 브랜드(200) 좋아요순 1페이지
SELECT p.* FROM products p WHERE p.deleted=false AND p.brand_id=200 ORDER BY (SELECT count(*) FROM product_likes l WHERE l.product_id=p.id) desc, p.id desc LIMIT 20 OFFSET 0;

-- EXPLAIN
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
| id | select_type        | table | partitions | type  | possible_keys | key                           | key_len | ref  | rows   | filtered | Extra                       |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
|  1 | PRIMARY            | p     | NULL       | ALL   | NULL          | NULL                          | NULL    | NULL |  99873 |     5.00 | Using where; Using filesort |
|  2 | DEPENDENT SUBQUERY | l     | NULL       | index | NULL          | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index    |
+----+--------------------+-------+------------+-------+---------------+-------------------------------+---------+------+--------+----------+-----------------------------+
-- EXPLAIN ANALYZE
-> Limit: 20 row(s)  (cost=10108 rows=20) (actual time=32946..32946 rows=20 loops=1)
    -> Sort: (select #2) DESC, p.id DESC, limit input to 20 row(s) per chunk  (cost=10108 rows=99873) (actual time=32946..32946 rows=20 loops=1)
        -> Filter: ((p.brand_id = 200) and (p.deleted = false))  (cost=10108 rows=99873) (actual time=0.303..31.4 rows=232 loops=1)
            -> Table scan on p  (cost=10108 rows=99873) (actual time=0.118..25.6 rows=100000 loops=1)
-> Select #2 (subquery in projection; dependent)
    -> Aggregate: count(0)  (cost=20207 rows=1) (actual time=142..142 rows=1 loops=232)
        -> Filter: (l.product_id = p.id)  (cost=10444 rows=97631) (actual time=34.5..142 rows=8.18 loops=232)
            -> Covering index scan on l using uk_product_likes_user_product  (cost=10444 rows=976305) (actual time=0.403..99.7 rows=974368 loops=232)


-- ==================== Q9_like_count_page: 목록 1페이지 상품 20개의 좋아요 수 일괄 집계 (countByProductIds)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                                     |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 976305 |    50.00 | Using where; Using index; Using temporary |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+-------------------------------------------+
-- EXPLAIN ANALYZE
-> Table scan on <temporary>  (actual time=188..188 rows=20 loops=1)
    -> Aggregate using temporary table  (actual time=188..188 rows=20 loops=1)
        -> Filter: (product_likes.product_id in (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20))  (cost=98312 rows=488152) (actual time=0.627..171 rows=94400 loops=1)
            -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=98312 rows=976305) (actual time=0.624..122 rows=974368 loops=1)


-- ==================== Q10_like_count_detail: 상품 상세의 좋아요 수 (인기 상품 1)
SELECT product_id, count(*) AS total FROM product_likes WHERE product_id IN (1) GROUP BY product_id;

-- EXPLAIN
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
| id | select_type | table         | partitions | type  | possible_keys                 | key                           | key_len | ref  | rows   | filtered | Extra                    |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
|  1 | SIMPLE      | product_likes | NULL       | index | uk_product_likes_user_product | uk_product_likes_user_product | 16      | NULL | 976305 |    10.00 | Using where; Using index |
+----+-------------+---------------+------------+-------+-------------------------------+-------------------------------+---------+------+--------+----------+--------------------------+
-- EXPLAIN ANALYZE
-> Group aggregate: count(0)  (cost=108075 rows=988) (actual time=148..148 rows=1 loops=1)
    -> Filter: (product_likes.product_id = 1)  (cost=98312 rows=97631) (actual time=0.412..146 rows=34627 loops=1)
        -> Covering index scan on product_likes using uk_product_likes_user_product  (cost=98312 rows=976305) (actual time=0.411..103 rows=974368 loops=1)


```

</details>

## API 측정: baseline (2026-10-14 09:28)

- 환경: Linux 6.18.44-fc-v114 (x86_64) / CPU Intel(R) Xeon(R) Processor @ 2.10GHz, 2코어 / 메모리 8GB / MySQL 8.0.46-0ubuntu0.24.04.4 / Java: openjdk version "21.0.12.1" 2026-08-18
- 앱: commerce-api local 프로필 + 명령행 인자 `--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=none --spring.jpa.show-sql=false --datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3306/loopers_perf --datasource.mysql-jpa.main.username=root --datasource.mysql-jpa.main.password=*** --server.port=8080 `
  개선 후(after) 측정도 같은 계정(root)·옵션으로 실행해야 비교할 수 있다.
- 데이터 보존 확인: loopers_perf.products 시작 전 100000 / 종료 후 100000
- 방식: curl 순차 1개, X-USER-ID: 1. 워밍업 후 측정 요청의 time_total 백분위(ms, nearest-rank).
  첫 요청이 5000ms를 넘으면 워밍업 1회 + 측정 3회로 줄인다. 표의 '표본'이 요청별 실제 측정 수다.
- 요청당 SQL: 측정 직전 events_statements_summary_by_digest를 비우고, 측정 후 SCHEMA_NAME=loopers_perf(앱 커넥션) 실행 수를 요청 수로 나눈 값
- 응답 스냅샷: `docs/week4/perf-results/baseline/api-*.tsv` (마지막 응답의 id·price·likeCount)
- 데이터: users 50000 / brands 200 (삭제 5) / products 100000 (미삭제 92859) / product_likes 974368
- 인덱스: brands.PRIMARY(id), product_likes.PRIMARY(id), product_likes.uk_product_likes_user_product(user_id,product_id), products.PRIMARY(id), users.PRIMARY(id)

| 요청 | 설명 | 경로 | 워밍업 | 표본 | p50 | p95 | p99 | 최대 | 200 응답 | 요청당 SQL | 요청당 SELECT | 요청당 SQL 시간(ms) |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| R1 | 브랜드 1 가격순 1페이지 (과제 측정 대상) | `/api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 20 | 200 | 153.6 | 170.9 | 183.6 | 1053.4 | 200/200 | 14.0 | 4.0 | 135.2 |
| R2 | 브랜드 200 가격순 1페이지 | `/api/v1/products?brandId=200&sort=price_asc&page=0&size=20` | 20 | 200 | 147.5 | 167.2 | 175.7 | 194.5 | 200/200 | 14.0 | 4.0 | 134.5 |
| R3 | 브랜드 200 좋아요순 1페이지 | `/api/v1/products?brandId=200&sort=likes_desc&page=0&size=20` | 1 | 3 | 19459.1 | 19736.5 | 19736.5 | 19736.5 | 3/3 | 14.0 | 4.0 | 19411.2 |
| R4 | 상품 1 상세 | `/api/v1/products/1` | 20 | 200 | 96.5 | 109.1 | 121.5 | 132.2 | 200/200 | 14.0 | 4.0 | 85.9 |

<details><summary>요청별 SQL digest</summary>

```text
-- R1 /api/v1/products?brandId=1&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
801	64.78	SET autocommit = ?
400	38.81	COMMIT
400	35.59	SET SESSION TRANSACTION READ ONLY
400	34.72	SET SESSION TRANSACTION READ WRITE
200	22060.22	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	4694.42	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	62.94	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	53.52	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
1	0.21	SELECT @@SESSION . auto_increment_increment AS auto_increment_increment , @@character_set_client AS character_set_client , @@character_set_connection AS charact
1	0.11	SET SESSION innodb_lock_wait_timeout = ?
1	0.06	SET character_set_results = ?
1	0.05	SELECT @@SESSION . transaction_read_only

-- R2 /api/v1/products?brandId=200&sort=price_asc&page=0&size=20 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	70.90	SET autocommit = ?
400	35.48	COMMIT
400	34.78	SET SESSION TRANSACTION READ ONLY
400	21.23	SET SESSION TRANSACTION READ WRITE
200	22120.13	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	4513.70	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY p . price ASC , p . id DESC LIMIT ? OFFSET ?
200	60.15	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	52.70	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)

-- R3 /api/v1/products?brandId=200&sort=likes_desc&page=0&size=20 (요청 3회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
12	1.35	SET autocommit = ?
6	0.60	COMMIT
6	0.45	SET SESSION TRANSACTION READ ONLY
6	0.28	SET SESSION TRANSACTION READ WRITE
3	57910.96	SELECT p . * FROM products p WHERE p . deleted = FALSE AND p . brand_id = ? ORDER BY ( SELECT COUNT ( * ) FROM product_likes l WHERE l . product_id = p . id ) D
3	318.06	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
3	0.96	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
3	0.86	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?

-- R4 /api/v1/products/1 (요청 200회 동안 앱 커넥션이 실행한 SQL: 실행 수 / 총 시간 ms / digest)
800	56.74	SET autocommit = ?
400	33.36	SET SESSION TRANSACTION READ ONLY
400	33.00	COMMIT
400	27.56	SET SESSION TRANSACTION READ WRITE
200	16887.87	SELECT product_id , COUNT ( * ) AS total FROM product_likes WHERE product_id IN (...) GROUP BY product_id
200	53.60	SELECT COUNT ( * ) FROM users uje1_0 WHERE uje1_0 . id = ?
200	40.68	SELECT bje1_0 . id , bje1_0 . deleted , bje1_0 . name FROM brands bje1_0 WHERE bje1_0 . id IN (...)
200	37.22	SELECT pje1_0 . id , pje1_0 . brand_id , pje1_0 . created_at , pje1_0 . deleted , pje1_0 . name , pje1_0 . price , pje1_0 . stock FROM products pje1_0 WHERE pje

```

</details>
