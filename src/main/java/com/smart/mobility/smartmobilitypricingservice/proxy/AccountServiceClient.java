package com.smart.mobility.smartmobilitypricingservice.proxy;

import com.smart.mobility.smartmobilitypricingservice.dto.DailySpentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "billing-service")
public interface AccountServiceClient {

    @GetMapping("/internal/accounts/daily-spent/{userId}")
    DailySpentResponse getDailySpent(@PathVariable String userId);
}
