package com.smart.mobility.smartmobilitypricingservice.controller;

import com.smart.mobility.smartmobilitypricingservice.model.DiscountRule;
import com.smart.mobility.smartmobilitypricingservice.model.FareSection;
import com.smart.mobility.smartmobilitypricingservice.model.TransportLine;
import com.smart.mobility.smartmobilitypricingservice.model.Zone;
import com.smart.mobility.smartmobilitypricingservice.repository.DiscountRuleRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.FareSectionRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.TransportLineRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final TransportLineRepository transportLineRepository;
    private final FareSectionRepository fareSectionRepository;
    private final ZoneRepository zoneRepository;
    private final DiscountRuleRepository discountRuleRepository;

    // --- Transport Line ---
    @PostMapping("/transport-lines")
    public ResponseEntity<TransportLine> createTransportLine(@RequestBody TransportLine transportLine) {
        return ResponseEntity.ok(transportLineRepository.save(transportLine));
    }

    @GetMapping("/transport-lines")
    public ResponseEntity<List<TransportLine>> getAllTransportLines() {
        return ResponseEntity.ok(transportLineRepository.findAll());
    }

    @DeleteMapping("/transport-lines/{id}")
    public ResponseEntity<Void> deleteTransportLine(@PathVariable Long id) {
        transportLineRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/transport-lines/{id}")
    public ResponseEntity<TransportLine> updateTransportLine(@PathVariable Long id,
            @RequestBody TransportLine transportLine) {
        return transportLineRepository.findById(id)
                .map(existingLine -> {
                    existingLine.setName(transportLine.getName());
                    existingLine.setTransportType(transportLine.getTransportType());
                    return ResponseEntity.ok(transportLineRepository.save(existingLine));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Fare Section ---
    @PostMapping("/fare-sections")
    public ResponseEntity<FareSection> createFareSection(@RequestBody FareSection fareSection) {
        return ResponseEntity.ok(fareSectionRepository.save(fareSection));
    }

    @GetMapping("/fare-sections")
    public ResponseEntity<List<FareSection>> getAllFareSections() {
        return ResponseEntity.ok(fareSectionRepository.findAll());
    }

    @DeleteMapping("/fare-sections/{id}")
    public ResponseEntity<Void> deleteFareSection(@PathVariable Long id) {
        fareSectionRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/fare-sections/{id}")
    public ResponseEntity<FareSection> updateFareSection(@PathVariable Long id,
            @RequestBody FareSection fareSection) {
        return fareSectionRepository.findById(id)
                .map(existingSection -> {
                    existingSection.setLineId(fareSection.getLineId());
                    existingSection.setSectionOrder(fareSection.getSectionOrder());
                    existingSection.setStationName(fareSection.getStationName());
                    existingSection.setZone(fareSection.getZone());
                    return ResponseEntity.ok(fareSectionRepository.save(existingSection));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // --- Zone ---
    @PostMapping("/zones")
    public ResponseEntity<Zone> createZone(@RequestBody Zone zone) {
        return ResponseEntity.ok(zoneRepository.save(zone));
    }

    @GetMapping("/zones")
    public ResponseEntity<List<Zone>> getAllZones() {
        return ResponseEntity.ok(zoneRepository.findAll());
    }

    // --- Discount Rule ---
    @PostMapping("/discount-rules")
    public ResponseEntity<DiscountRule> createDiscountRule(@RequestBody DiscountRule discountRule) {
        return ResponseEntity.ok(discountRuleRepository.save(discountRule));
    }

    @GetMapping("/discount-rules")
    public ResponseEntity<List<DiscountRule>> getAllDiscountRules() {
        return ResponseEntity.ok(discountRuleRepository.findAll());
    }

    @DeleteMapping("/discount-rules/{id}")
    public ResponseEntity<Void> deleteDiscountRule(@PathVariable Long id) {
        discountRuleRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/discount-rules/{id}")
    public ResponseEntity<DiscountRule> updateDiscountRule(@PathVariable Long id,
            @RequestBody DiscountRule discountRule) {
        return discountRuleRepository.findById(id)
                .map(existingRule -> {
                    existingRule.setRuleType(discountRule.getRuleType());
                    existingRule.setPercentage(discountRule.getPercentage());
                    existingRule.setPriority(discountRule.getPriority());
                    existingRule.setCondition(discountRule.getCondition());
                    existingRule.setStartHour(discountRule.getStartHour());
                    existingRule.setEndHour(discountRule.getEndHour());
                    existingRule.setActive(discountRule.getActive());
                    existingRule.setStartDay(discountRule.getStartDay());
                    existingRule.setEndDay(discountRule.getEndDay());
                    existingRule.setStartDate(discountRule.getStartDate());
                    existingRule.setEndDate(discountRule.getEndDate());
                    return ResponseEntity.ok(discountRuleRepository.save(existingRule));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
