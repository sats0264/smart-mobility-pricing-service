package com.smart.mobility.smartmobilitypricingservice.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.mobility.smartmobilitypricingservice.dto.PricingContextDTO;
import com.smart.mobility.smartmobilitypricingservice.dto.SubscriptionContextDTO;
import com.smart.mobility.smartmobilitypricingservice.enums.TransportType;
import com.smart.mobility.smartmobilitypricingservice.model.PricingResult;
import com.smart.mobility.smartmobilitypricingservice.dto.TripCompletedEvent;
import com.smart.mobility.smartmobilitypricingservice.dto.TripPricedEvent;
import com.smart.mobility.smartmobilitypricingservice.model.FareSection;
import com.smart.mobility.smartmobilitypricingservice.repository.FareSectionRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.PricingResultRepository;
import com.smart.mobility.smartmobilitypricingservice.proxy.UserServiceClient;
import com.smart.mobility.smartmobilitypricingservice.messaging.PricingEventPublisher;
import com.smart.mobility.smartmobilitypricingservice.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

        @Mock
        private PricingResultRepository pricingResultRepository;

        @Mock
        private FareSectionRepository fareSectionRepository;

        @Mock
        private UserServiceClient userServiceClient;

        @Mock
        private PricingEventPublisher pricingEventPublisher;

        @Spy
        private ObjectMapper objectMapper = new ObjectMapper();

        @InjectMocks
        private PricingService pricingService;

        @BeforeEach
        void setUp() {
                lenient().when(pricingResultRepository.save(any(PricingResult.class))).thenAnswer(invocation -> {
                        PricingResult result = invocation.getArgument(0);
                        result.setId(1L);
                        return result;
                });
        }

        @Test
        void testCalculateAndProcessTrip_BUS_NoDiscounts_UnderCap() {
                TripCompletedEvent event = TripCompletedEvent.builder()
                                .tripId(101L)
                                .userId("user-123")
                                .transportType("BUS")
                                .transportLineId(1L)
                                .startLocation("A")
                                .endLocation("D") // 3 sections distance (0 to 3)
                                .build();

                FareSection s1 = FareSection.builder().lineId(1L).stationName("A").sectionOrder(0).build();
                FareSection s2 = FareSection.builder().lineId(1L).stationName("D").sectionOrder(3).build();
                when(fareSectionRepository.findByLineIdOrderBySectionOrderAsc(1L)).thenReturn(List.of(s1, s2));

                PricingContextDTO context = PricingContextDTO.builder()
                                .hasActivePass(true)
                                .dailyCapAmount(2500.0)
                                .activeSubscriptions(Collections.emptyList())
                                .build();

                when(userServiceClient.getPricingContext("user-123")).thenReturn(context);

                pricingService.calculateAndProcessTrip(event);

                ArgumentCaptor<PricingResult> resultCaptor = ArgumentCaptor.forClass(PricingResult.class);
                verify(pricingResultRepository).save(resultCaptor.capture());
                PricingResult savedResult = resultCaptor.getValue();

                assertThat(savedResult.getBasePrice()).isEqualByComparingTo(BigDecimal.valueOf(300));
                assertThat(savedResult.getFinalAmount()).isEqualByComparingTo(BigDecimal.valueOf(300));

                verify(pricingEventPublisher).publishTripPricedEvent(any(TripPricedEvent.class));
        }

        @Test
        void testCalculateAndProcessTrip_TER_WithDiscount_HittingCap() {
                TripCompletedEvent event = TripCompletedEvent.builder()
                                .tripId(102L)
                                .userId("user-456")
                                .transportType("TER")
                                .transportLineId(2L)
                                .startLocation("X")
                                .endLocation("Z") // 2 sections distance (0 to 2)
                                .build();

                FareSection s1 = FareSection.builder().lineId(2L).stationName("X").sectionOrder(0).build();
                FareSection s2 = FareSection.builder().lineId(2L).stationName("Z").sectionOrder(2).build();
                when(fareSectionRepository.findByLineIdOrderBySectionOrderAsc(2L)).thenReturn(List.of(s1, s2));

                PricingContextDTO context = PricingContextDTO.builder()
                                .hasActivePass(true)
                                .dailyCapAmount(2500.0)
                                .activeSubscriptions(List.of(
                                                SubscriptionContextDTO.builder()
                                                                .applicableTransport(TransportType.TER)
                                                                .discountPercentage(20.0)
                                                                .build()))
                                .build();

                when(userServiceClient.getPricingContext("user-456")).thenReturn(context);

                pricingService.calculateAndProcessTrip(event);

                ArgumentCaptor<PricingResult> resultCaptor = ArgumentCaptor.forClass(PricingResult.class);
                verify(pricingResultRepository).save(resultCaptor.capture());
                PricingResult savedResult = resultCaptor.getValue();

                assertThat(savedResult.getBasePrice()).isEqualByComparingTo(BigDecimal.valueOf(1500));
                // Base 1500 - 20% = 1200. Remaining cap 500. Final should be 500.
                assertThat(savedResult.getFinalAmount()).isEqualByComparingTo(BigDecimal.valueOf(500));
        }
}
