package com.mamoji.repository;

import com.mamoji.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/**
 * Repository for transaction categories.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {
    /**
     * Finds categories by type in ascending id order.
     */
    List<Category> findByTypeOrderByIdAsc(Integer type);

    /**
     * Finds family categories plus system presets.
     */
    List<Category> findByFamilyIdOrIsSystem(Long familyId, Integer isSystem);
}
