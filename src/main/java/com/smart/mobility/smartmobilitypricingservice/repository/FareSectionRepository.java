package com.smart.mobility.smartmobilitypricingservice.repository;

import com.smart.mobility.smartmobilitypricingservice.model.FareSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FareSectionRepository extends JpaRepository<FareSection, Long> {
    List<FareSection> findByLineIdOrderBySectionOrderAsc(Long lineId);
}
