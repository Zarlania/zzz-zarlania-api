package com.zarlania.api.features.entity;

import com.zarlania.api.persistence.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A per-organization override of a toggle's percentage; when present it wins unconditionally over
 * the toggle's global percentage. References the organization by opaque id only — never the {@code
 * organizations} entity (ADR-0011); referential integrity is enforced by the DB foreign key.
 */
@Entity
@Table(name = "feature_toggle_org_overrides")
@Getter
@NoArgsConstructor
public class FeatureToggleOrgOverrideEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "toggle_id", nullable = false)
  private FeatureToggleEntity toggle;

  @Setter
  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  /** Override percentage: 0 = off, 100 = on, in between = partial (per-request coin flip). */
  @Setter
  @Column(name = "percentage", nullable = false)
  private int percentage;
}
