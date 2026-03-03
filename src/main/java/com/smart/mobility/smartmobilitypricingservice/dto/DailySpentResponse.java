package com.smart.mobility.smartmobilitypricingservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailySpentResponse {
    private String userId;
    private Double dailySpent;
}
