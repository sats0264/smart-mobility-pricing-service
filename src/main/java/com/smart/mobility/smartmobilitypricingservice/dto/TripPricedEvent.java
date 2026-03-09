package com.smart.mobility.smartmobilitypricingservice.dto;

import lombok.Builder;
import java.math.BigDecimal;
import java.util.List;

@Builder
public record TripPricedEvent(
        Long tripId,
        String userId,
        BigDecimal basePrice,
        List<AppliedDiscountDto> appliedDiscounts,
        BigDecimal finalAmount,
        boolean penalty,
        boolean countTowardsDailyCap) {
}
