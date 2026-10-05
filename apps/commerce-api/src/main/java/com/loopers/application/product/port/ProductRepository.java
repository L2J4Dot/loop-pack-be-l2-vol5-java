package com.loopers.application.product.port;

import com.loopers.domain.brand.BrandId;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    List<Product> findPage(int page, int size);

    List<Product> search(Long brandId, int page, int size, String sort);

    List<Product> findAllByIds(Collection<ProductId> ids);

    Product save(Product product);

    Optional<Product> findById(ProductId id);

    Optional<Product> findByIdForUpdate(ProductId id);

    boolean existsActiveByBrandId(BrandId id);
}
