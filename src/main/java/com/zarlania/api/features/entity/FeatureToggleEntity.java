package com.zarlania.api.features.entity;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A feature toggle's stored state. The toggle itself is registered in code (the {@code Feature}
 * enum); this row holds only its runtime percentage. Internal to the {@code features} domain;
 * crosses boundaries via the {@link com.zarlania.api.features.dto.FeatureToggle} DTO.
 */
@Entity
@Table(name = "feature_toggles")
@Getter
@NoArgsConstructor
public class FeatureToggleEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "name", nullable = false, length = 100)
  private String name;

  /** Rollout percentage: 0 = off, 100 = on, in between = partial (per-request coin flip). */
  @Setter
  @Column(name = "percentage", nullable = false)
  private int percentage;

  /**
   * Human-readable description, code-owned (the {@code Feature} enum) and written only by the
   * startup synchronizer. Defaults to empty string — the pre-sync placeholder that mirrors the
   * migration's {@code DEFAULT ''} — so an entity persisted before its description is set (e.g. in
   * a test) never violates the {@code NOT NULL} column.
   */
  @Setter
  @Column(name = "description", nullable = false, length = 500)
  private String description = "";
}
