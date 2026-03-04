package com.smart.mobility.smartmobilitypricingservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "fare_sections")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FareSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(name = "section_order", nullable = false)
    private Integer sectionOrder;

    @Column(name = "station_name", length = 100)
    private String stationName;

    @Column(name = "zone")
    private Integer zone;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
