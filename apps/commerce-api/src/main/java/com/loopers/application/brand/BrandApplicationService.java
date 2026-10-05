package com.loopers.application.brand;

import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.RuleViolationException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BrandApplicationService {
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;

    public BrandApplicationService(BrandRepository brandRepository, ProductRepository productRepository) {
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public BrandResult create(String name) {
        return BrandResult.from(brandRepository.save(Brand.create(name)));
    }

    @Transactional
    public BrandResult change(long id, String name) {
        Brand brand = brandRepository.findByIdForUpdate(new BrandId(id)).orElseThrow(BrandNotFoundException::new);
        brand.changeName(name);
        return BrandResult.from(brandRepository.save(brand));
    }

    @Transactional
    public void delete(long id) {
        Brand brand = brandRepository.findByIdForUpdate(new BrandId(id)).orElseThrow(BrandNotFoundException::new);
        if (brand.isDeleted()) {
            return;
        }
        if (productRepository.existsActiveByBrandId(brand.getId())) {
            throw new RuleViolationException("미삭제 상품이 연결된 브랜드는 삭제할 수 없습니다.");
        }
        brand.delete();
        brandRepository.save(brand);
    }

    @Transactional(readOnly = true)
    public List<AdminBrandResult> list(int page, int size) {
        return brandRepository.findPage(page, size).stream().map(AdminBrandResult::from).toList();
    }

    @Transactional(readOnly = true)
    public AdminBrandResult getAdminBrand(long id) {
        return AdminBrandResult.from(brandRepository.findById(new BrandId(id)).orElseThrow(BrandNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public BrandResult getBrand(BrandId id) {
        Brand brand = brandRepository.findById(id)
            .filter(found -> !found.isDeleted())
            .orElseThrow(BrandNotFoundException::new);
        return BrandResult.from(brand);
    }
}
