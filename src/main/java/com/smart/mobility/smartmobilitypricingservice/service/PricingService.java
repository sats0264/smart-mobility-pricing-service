package com.smart.mobility.smartmobilitypricingservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.mobility.smartmobilitypricingservice.dto.PricingContextDTO;
import com.smart.mobility.smartmobilitypricingservice.dto.SubscriptionContextDTO;
import com.smart.mobility.smartmobilitypricingservice.dto.TripCompletedEvent;
import com.smart.mobility.smartmobilitypricingservice.dto.PricingResponseDTO;
import com.smart.mobility.smartmobilitypricingservice.dto.AppliedDiscountDto;
import com.smart.mobility.smartmobilitypricingservice.dto.TripPricedEvent;
import com.smart.mobility.smartmobilitypricingservice.model.DiscountRule;
import com.smart.mobility.smartmobilitypricingservice.model.FareSection;
import com.smart.mobility.smartmobilitypricingservice.model.PricingResult;
import com.smart.mobility.smartmobilitypricingservice.proxy.AccountServiceClient;
import com.smart.mobility.smartmobilitypricingservice.repository.DiscountRuleRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.FareSectionRepository;
import com.smart.mobility.smartmobilitypricingservice.repository.PricingResultRepository;
import com.smart.mobility.smartmobilitypricingservice.proxy.UserServiceClient;
import com.smart.mobility.smartmobilitypricingservice.messaging.PricingEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service de tarification dynamique.
 * Calcule le prix final des trajets en fonction du transport, des abonnements,
 * des règles de réduction et des plafonds journaliers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PricingService {

    // Référentiels pour les règles de remise et les résultats d'audit
    private final DiscountRuleRepository discountRuleRepository;
    private final PricingResultRepository pricingResultRepository;
    private final FareSectionRepository fareSectionRepository;

    // Clients pour la communication inter-services (User Service)
    private final UserServiceClient userServiceClient;

    // Client pour la communication inter-services (Billing Service)
    private final AccountServiceClient accountServiceClient;

    // Publication des événements de prix vers RabbitMQ
    private final PricingEventPublisher pricingEventPublisher;

    // Utilitaire pour transformer les objets en JSON (Audit)
    private final ObjectMapper objectMapper;

    /**
     * Calcule le prix final d'un trajet et publie le résultat.
     */
    @Transactional
    public PricingResponseDTO calculateAndProcessTrip(TripCompletedEvent event) {
        log.info("Calculating dynamic pricing for trip {} and user {}", event.tripId(), event.userId());

        // 1a. Récupération du contexte utilisateur (Abonnements, Plafond)
        PricingContextDTO summary = fetchUserSummary(event.userId());

        // 1b. Récupération du daily spent
        Double dailySpent = accountServiceClient.getDailySpent(event.userId()).getDailySpent();

        // 2. Prix de base (BUS, TER, BRT)
        BigDecimal basePrice;
        boolean penalty = event.mismatch();
        List<AppliedDiscountDto> appliedDiscounts = new ArrayList<>();

        if (penalty) {
            // Apply Penalty (Strategy 1)
            basePrice = switch (event.transportType().toUpperCase()) {
                case "BRT" -> BigDecimal.valueOf(1000);
                case "TER" -> BigDecimal.valueOf(2500);
                // For BUS, we apply a fixed penalty fare instead of calculating based on sections, to ensure deterrence
                default -> BigDecimal.valueOf(600);
            };
            appliedDiscounts.add(AppliedDiscountDto.builder()
                    .ruleType("PENALTY_MISMATCH")
                    .percentage(BigDecimal.ZERO)
                    .amountDeducted(BigDecimal.ZERO)
                    .build());
            log.info("Transport line mismatch detected for trip {}. Applying max penalty fare: {}", event.tripId(),
                    basePrice);
        } else {
            basePrice = calculateBasePrice(event);
        }

        // 3a. Réduction par ABONNEMENT (la meilleure remise applicable)
        // Subscription and other discounts are NOT applied if it's a penalty
        BigDecimal amountAfterSub = penalty ? basePrice
                : applySubscriptionDiscount(basePrice, event, summary, appliedDiscounts);

        // 3b. Autres règles de remise (BDD : OFFPEAK, etc.)
        BigDecimal amountAfterDatabaseRules = penalty ? amountAfterSub
                : applyDatabaseDiscountRules(amountAfterSub, event, appliedDiscounts);

        // 4. Gestion du Daily Cap (Plafond Journalier)
        // Daily Cap is also bypassed for penalties to ensure deterrence
        BigDecimal finalAmount = amountAfterDatabaseRules.max(BigDecimal.ZERO);
        boolean capReached = false;

        if (!penalty) {
            BigDecimal dailyCap = BigDecimal
                    .valueOf(summary.getDailyCapAmount() != null ? summary.getDailyCapAmount() : 0.0);
            BigDecimal currentSpent = BigDecimal
                    .valueOf(dailySpent != null ? dailySpent : 0.0);
            BigDecimal remainingCap = dailyCap.subtract(currentSpent).max(BigDecimal.ZERO);

            if (summary.isHasActivePass() && remainingCap.compareTo(BigDecimal.ZERO) <= 0
                    && dailyCap.compareTo(BigDecimal.ZERO) > 0) {
                // Plafond atteint : Gratuité
                finalAmount = BigDecimal.ZERO;
                capReached = true;
                appliedDiscounts.add(AppliedDiscountDto.builder()
                        .ruleType("DAILY_CAP")
                        .percentage(BigDecimal.valueOf(100))
                        .amountDeducted(amountAfterDatabaseRules)
                        .build());
            } else if (summary.isHasActivePass() && amountAfterDatabaseRules.compareTo(remainingCap) > 0
                    && dailyCap.compareTo(BigDecimal.ZERO) > 0) {
                // Dépassement du plafond : On ne facture que le reste
                BigDecimal capDiscount = amountAfterDatabaseRules.subtract(remainingCap);
                finalAmount = remainingCap;
                capReached = true;
                appliedDiscounts.add(AppliedDiscountDto.builder()
                        .ruleType("DAILY_CAP_LIMIT")
                        .percentage(BigDecimal.ZERO)
                        .amountDeducted(capDiscount)
                        .build());
            }
        }

        BigDecimal totalDiscountApplied = basePrice.subtract(finalAmount);

        // 5. Audit & Log
        savePricingAudit(event, basePrice, totalDiscountApplied, finalAmount, appliedDiscounts);

        // 6. Publication de l'événement vers Billing Service (avec flag penalty)
        publishTripPricedEvent(event, basePrice, finalAmount, appliedDiscounts, penalty);

        return PricingResponseDTO.builder()
                .basePrice(basePrice)
                .discountApplied(totalDiscountApplied)
                .finalPrice(finalAmount)
                .capReached(capReached)
                .build();
    }

    /**
     * Récupère le résultat de tarification pour un trajet donné.
     */
    @Transactional(readOnly = true)
    public PricingResult getPricingByTripId(Long tripId) {
        return pricingResultRepository.findByTripId(tripId).orElse(null);
    }

    /**
     * Applique la meilleure réduction d'abonnement disponible.
     */
    private BigDecimal applySubscriptionDiscount(BigDecimal price, TripCompletedEvent event, PricingContextDTO summary,
            List<AppliedDiscountDto> discounts) {

        double maxDiscountRate = summary.getActiveSubscriptions().stream()
                .filter(sub -> isSubscriptionApplicable(sub, event))
                .mapToDouble(SubscriptionContextDTO::getDiscountPercentage)
                .max()
                .orElse(0.0);

        if (maxDiscountRate > 0) {
            BigDecimal rate = BigDecimal.valueOf(maxDiscountRate).divide(BigDecimal.valueOf(100));
            BigDecimal amount = price.multiply(rate).setScale(2, RoundingMode.HALF_UP);

            discounts.add(AppliedDiscountDto.builder()
                    .ruleType("SUBSCRIPTION")
                    .percentage(BigDecimal.valueOf(maxDiscountRate))
                    .amountDeducted(amount)
                    .build());

            return price.subtract(amount);
        }
        return price;
    }

    /**
     * Vérifie si un abonnement est valide pour ce transport.
     */
    private boolean isSubscriptionApplicable(SubscriptionContextDTO sub, TripCompletedEvent event) {
        if (sub.getApplicableTransport() == null)
            return false;
        if (sub.getApplicableTransport().name().equals("ALL"))
            return true;
        return sub.getApplicableTransport().name().equalsIgnoreCase(event.transportType());
    }

    /**
     * Applique les règles de remise stockées en base de données (ex: OFFPEAK).
     */
    private BigDecimal applyDatabaseDiscountRules(BigDecimal currentAmount, TripCompletedEvent event,
            List<AppliedDiscountDto> appliedDiscounts) {
        List<DiscountRule> activeRules = discountRuleRepository.findByActiveTrueOrderByPriorityAsc();
        BigDecimal amount = currentAmount;

        for (DiscountRule rule : activeRules) {
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

    /**
     * Vérifie l'applicabilité d'une règle selon sa condition (transport) et ses
     * horaires (off-peak).
     */
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
                if (tripTime.isBefore(rule.getStartHour()) || tripTime.isAfter(rule.getEndHour())) {
                    return false;
                }
            }
            // Cas nocturne : 22:00 -> 05:00
            else {
                if (tripTime.isBefore(rule.getStartHour()) && tripTime.isAfter(rule.getEndHour())) {
                    return false;
                }
            }
        }

        // 3. Vérification des dates de validité (ex: Vacances)
        if (rule.getStartDate() != null && rule.getEndDate() != null) {
            java.time.LocalDate tripDate = event.startTime().toLocalDate();
            if (tripDate.isBefore(rule.getStartDate()) || tripDate.isAfter(rule.getEndDate())) {
                return false;
            }
        }

        // 4. Vérification des jours de validité (ex: Week-end)
        if (rule.getStartDay() != null && rule.getEndDay() != null) {
            int tripDay = event.startTime().getDayOfWeek().getValue(); // 1 (Mon) - 7 (Sun)

            // Cas classique : Lundi -> Vendredi (1 -> 5)
            if (rule.getStartDay() <= rule.getEndDay()) {
                if (tripDay < rule.getStartDay() || tripDay > rule.getEndDay()) {
                    return false;
                }
            }
            // Cas chevauchement : Vendredi -> Lundi (5 -> 1)
            else {
                if (tripDay < rule.getStartDay() && tripDay > rule.getEndDay()) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Barème officiel : BUS (150+50/sect), TER (500+500/sect), BRT (400 ou 500).
     */
    private BigDecimal calculateBasePrice(TripCompletedEvent event) {
        if (event.transportLineId() == null || event.startLocation() == null || event.endLocation() == null) {
            log.warn("Données de trajet incomplètes pour le calcul du prix : {}", event);
            return BigDecimal.ZERO;
        }

        List<FareSection> sections = fareSectionRepository.findByLineIdOrderBySectionOrderAsc(event.transportLineId());

        FareSection startSection = sections.stream()
                .filter(s -> s.getStationName().equalsIgnoreCase(event.startLocation()))
                .findFirst().orElse(null);

        FareSection endSection = sections.stream()
                .filter(s -> s.getStationName().equalsIgnoreCase(event.endLocation()))
                .findFirst().orElse(null);

        if (startSection == null || endSection == null) {
            log.error("Stations non trouvées pour la ligne {} : {} -> {}", event.transportLineId(),
                    event.startLocation(), event.endLocation());
            return BigDecimal.ZERO;
        }

        int startOrder = startSection.getSectionOrder();
        int endOrder = endSection.getSectionOrder();

        if ("BRT".equalsIgnoreCase(event.transportType())) {
            Integer startZone = startSection.getZone();
            Integer endZone = endSection.getZone();
            return BigDecimal.valueOf((startZone != null && endZone != null && startZone.equals(endZone)) ? 400 : 500);
        }

        // Logic for BUS and TER: count distinct zones traversed
        int minOrder = Math.min(startOrder, endOrder);
        int maxOrder = Math.max(startOrder, endOrder);

        long distinctZones = sections.stream()
                .filter(s -> s.getSectionOrder() >= minOrder && s.getSectionOrder() <= maxOrder)
                .map(FareSection::getZone)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();

        long numZonesAdded = Math.max(0, distinctZones - 1);

        if ("BUS".equalsIgnoreCase(event.transportType())) {
            return BigDecimal.valueOf(150 + (numZonesAdded * 50));
        } else if ("TER".equalsIgnoreCase(event.transportType())) {
            return BigDecimal.valueOf(500 + (numZonesAdded * 500));
        }
        return BigDecimal.ZERO;
    }

    /**
     * Récupère le contexte utilisateur ou renvoie un profil par défaut si hors
     * ligne.
     */
    private PricingContextDTO fetchUserSummary(String userId) {
        try {
            return userServiceClient.getPricingContext(userId);
        } catch (Exception e) {
            log.warn("UserService injoignable pour {}, utilisation des valeurs par défaut", userId);
            return PricingContextDTO.builder()
                    .hasActivePass(false)
                    .dailyCapAmount(2500.0)
                    .activeSubscriptions(new ArrayList<>())
                    .build();
        }
    }

    /**
     * Sauvegarde du calcul en BDD pour audit.
     */
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

    /**
     * Publie le prix final vers RabbitMQ.
     */
    private void publishTripPricedEvent(TripCompletedEvent event, BigDecimal base, BigDecimal fin,
            List<AppliedDiscountDto> list, boolean penalty) {
        boolean countTowardsDailyCap = !penalty; // Les pénalités ne doivent pas être comptées dans le daily cap
        TripPricedEvent pricedEvent = TripPricedEvent.builder()
                .tripId(event.tripId()).userId(event.userId())
                .basePrice(base).appliedDiscounts(list).finalAmount(fin)
                .penalty(penalty)
                .countTowardsDailyCap(countTowardsDailyCap)
                .build();
        pricingEventPublisher.publishTripPricedEvent(pricedEvent);
    }
}
