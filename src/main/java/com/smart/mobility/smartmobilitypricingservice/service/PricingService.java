package com.smart.mobility.smartmobilitypricingservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.mobility.smartmobilitypricingservice.dto.*;
import com.smart.mobility.smartmobilitypricingservice.model.DiscountRule;
import com.smart.mobility.smartmobilitypricingservice.model.PricingResult;
import com.smart.mobility.smartmobilitypricingservice.proxy.AccountServiceClient;
import com.smart.mobility.smartmobilitypricingservice.repository.DiscountRuleRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.PricingResultRepository;
import com.smart.mobility.smartmobilitypricingservice.proxy.UserServiceClient;
import com.smart.mobility.smartmobilitypricingservice.messaging.PricingEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    private final DiscountRuleRepository discountRuleRepository;
    private final PricingResultRepository pricingResultRepository;
    private final UserServiceClient userServiceClient;

    // Client pour la communication inter-services (Billing Service)
    private final AccountServiceClient accountServiceClient;

    private final PricingEventPublisher pricingEventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public PricingResponseDTO calculateAndProcessTrip(TripCompletedEvent event) {
        log.info("Calculating dynamic pricing for trip {} and user {}", event.tripId(), event.userId());

        // 1. Récupération du résumé utilisateur (Abonnement, Cap journalier,
        // Consommation actuelle)
        UserMobilitySummaryDTO summary = fetchUserSummary(event.userId());

        // 2. Calcul du tarif de base Dakar (BUS, TER, BRT)
        // 1b. Récupération du daily spent
        Double dailySpent = accountServiceClient.getDailySpent(event.userId()).getDailySpent();

        BigDecimal basePrice = calculateBasePrice(event);

        // 3. Application des réductions en chaîne
        List<AppliedDiscountDto> appliedDiscounts = new ArrayList<>();

        // a. Réduction par ABONNEMENT (déduite de User Service via Summary)
        BigDecimal amountAfterSub = applySubscriptionDiscount(basePrice, summary, appliedDiscounts);

        // b. Autres réductions stockées en BDD (OFFPEAK, LOYALTY, etc.)
        BigDecimal amountAfterDatabaseRules = applyDatabaseDiscountRules(amountAfterSub, event, appliedDiscounts);

        // 4. Gestion du Daily Cap (Plafond Journalier)
        BigDecimal dailyCap = BigDecimal.valueOf(summary.getDailyCap());
        BigDecimal currentSpent = BigDecimal.valueOf(summary.getCurrentSpent());
        BigDecimal remainingCap = dailyCap.subtract(currentSpent).max(BigDecimal.ZERO);

        BigDecimal finalAmount;
        boolean capReached = false;

        if (remainingCap.compareTo(BigDecimal.ZERO) <= 0) {
            // Plafond déjà atteint : Trajet Gratuit
            finalAmount = BigDecimal.ZERO;
            capReached = true;
            appliedDiscounts.add(AppliedDiscountDto.builder()
                    .ruleType("DAILY_CAP")
                    .percentage(BigDecimal.valueOf(100))
                    .amountDeducted(amountAfterDatabaseRules)
                    .build());
        } else if (amountAfterDatabaseRules.compareTo(remainingCap) > 0) {
            // Le trajet fait dépasser le plafond : On ne facture que le restant du plafond
            BigDecimal capDiscount = amountAfterDatabaseRules.subtract(remainingCap);
            finalAmount = remainingCap;
            capReached = true;
            appliedDiscounts.add(AppliedDiscountDto.builder()
                    .ruleType("DAILY_CAP_LIMIT")
                    .percentage(BigDecimal.ZERO)
                    .amountDeducted(capDiscount)
                    .build());
        } else {
            // Encore sous le plafond : Prix calculé conservé
            finalAmount = amountAfterDatabaseRules.max(BigDecimal.ZERO);
        }

        BigDecimal totalDiscountApplied = basePrice.subtract(finalAmount);

        // 5. Audit & Log du résultat
        savePricingAudit(event, basePrice, totalDiscountApplied, finalAmount, appliedDiscounts);

        // 6. Publication de l'événement pour Billing & Trip Management
        publishTripPricedEvent(event, basePrice, finalAmount, appliedDiscounts);

        return PricingResponseDTO.builder()
                .basePrice(basePrice)
                .discountApplied(totalDiscountApplied)
                .finalPrice(finalAmount)
                .capReached(capReached)
                .build();
    }

    private BigDecimal applySubscriptionDiscount(BigDecimal price, UserMobilitySummaryDTO summary,
            List<AppliedDiscountDto> discounts) {
        if (summary.getActiveDiscountRate() > 0) {
            BigDecimal rate = BigDecimal.valueOf(summary.getActiveDiscountRate());
            BigDecimal amount = price.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            discounts.add(AppliedDiscountDto.builder()
                    .ruleType("SUBSCRIPTION")
                    .percentage(rate.multiply(BigDecimal.valueOf(100)))
                    .amountDeducted(amount)
                    .build());

            return price.subtract(amount);
        }
        return price;
    }

    private BigDecimal applyDatabaseDiscountRules(BigDecimal currentAmount, TripCompletedEvent event,
            List<AppliedDiscountDto> appliedDiscounts) {
        List<DiscountRule> activeRules = discountRuleRepository.findByActiveTrueOrderByPriorityAsc();
        BigDecimal amount = currentAmount;

        for (DiscountRule rule : activeRules) {
            // On ignore SUBSCRIPTION ici, car géré via summary pour plus de précision temps
            // réel
            if ("SUBSCRIPTION".equalsIgnoreCase(rule.getRuleType()))
                continue;

            if (isRuleApplicable(rule, event)) {
                BigDecimal percentage = rule.getPercentage();
                BigDecimal discountFactor = percentage.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                BigDecimal deduction = amount.multiply(discountFactor).setScale(2, RoundingMode.HALF_UP);

                appliedDiscounts.add(AppliedDiscountDto.builder()
                        .ruleType(rule.getRuleType())
                        .percentage(percentage)
                        .amountDeducted(deduction)
                        .build());

                amount = amount.subtract(deduction);
            }
        }
        return amount;
    }

    private boolean isRuleApplicable(DiscountRule rule, TripCompletedEvent event) {
        // 1. Vérification du type de transport (Condition)
        boolean transportMatch = false;
        if (rule.getCondition() == null || rule.getCondition().isEmpty()
                || "ALL".equalsIgnoreCase(rule.getCondition())) {
            transportMatch = true;
        } else if (rule.getCondition().contains(event.transportType())) {
            transportMatch = true;
        }

        if (!transportMatch)
            return false;

        // 2. Vérification des horaires (Heures creuses / Off-peak)
        if (rule.getStartHour() != null && rule.getEndHour() != null) {
            LocalTime tripTime = event.startTime().toLocalTime();

            // Cas classique : 09:00 -> 17:00
            if (rule.getStartHour().isBefore(rule.getEndHour())) {
                return !tripTime.isBefore(rule.getStartHour()) && !tripTime.isAfter(rule.getEndHour());
            }
            // Cas nocturne : 22:00 -> 05:00
            else {
                return !tripTime.isBefore(rule.getStartHour()) || !tripTime.isAfter(rule.getEndHour());
            }
        }

        return true;
    }

    private BigDecimal calculateBasePrice(TripCompletedEvent event) {
        int sections = event.numberOfSections() != null ? event.numberOfSections() : 0;
        if ("BUS".equalsIgnoreCase(event.transportType())) {
            return BigDecimal.valueOf(150 + (sections * 50L));
        } else if ("TER".equalsIgnoreCase(event.transportType())) {
            return BigDecimal.valueOf(500 + (sections * 500L));
        } else if ("BRT".equalsIgnoreCase(event.transportType())) {
            int startZone = event.startZone() != null ? event.startZone() : -1;
            int endZone = event.endZone() != null ? event.endZone() : -1;
            return BigDecimal.valueOf((startZone == endZone && startZone != -1) ? 400 : 500);
        }
        return BigDecimal.ZERO;
    }

    private UserMobilitySummaryDTO fetchUserSummary(String userId) {
        try {
            return userServiceClient.getUserSummary(userId);
        } catch (Exception e) {
            log.warn("UserService injoignable pour {}, utilisation des valeurs par défaut", userId);
            return UserMobilitySummaryDTO.builder()
                    .hasActivePass(false)
                    .dailyCap(25.0).currentSpent(0.0).activeDiscountRate(0.0)
                    .build();
        }
    }

    private void savePricingAudit(TripCompletedEvent event, BigDecimal base, BigDecimal disc, BigDecimal fin,
            List<AppliedDiscountDto> list) {
        try {
            String json = objectMapper.writeValueAsString(list);
            PricingResult result = PricingResult.builder()
                    .tripId(event.tripId())
                    .userId(event.userId())
                    .transportType(event.transportType())
                    .basePrice(base).discountApplied(disc).finalAmount(fin)
                    .appliedDiscounts(json).build();
            pricingResultRepository.save(result);
        } catch (JsonProcessingException e) {
            log.error("Audit pricing échoué", e);
        }
    }

    private void publishTripPricedEvent(TripCompletedEvent event, BigDecimal base, BigDecimal fin,
            List<AppliedDiscountDto> list) {
        TripPricedEvent pricedEvent = TripPricedEvent.builder()
                .tripId(event.tripId()).userId(event.userId())
                .basePrice(base).appliedDiscounts(list).finalAmount(fin).build();
        pricingEventPublisher.publishTripPricedEvent(pricedEvent);
    }
}
