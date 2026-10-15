package com.loopers.application.like.port;

import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface LikeRepository {
    // 관계가 새로 생기면 true, 이미 있으면 false
    boolean save(Like like);

    // 관계를 실제로 지웠으면 true, 없었으면 false
    boolean delete(Like like);

    Map<ProductId, Long> countByProductIds(Collection<ProductId> ids);
    List<ProductId> findActiveProductIds(long userId, int page, int size);
}
