package com.zarlania.api.identity.entity;

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
 * A user's password credential — the first credential type owned by the {@code identity} domain.
 * References the owning user by id only (a DB foreign key, no JPA association) per ADR-0011; one
 * credential per user (unique {@code user_id}). Holds only the encoded hash, never plaintext.
 * Future credential types (e.g. OAuth identities) are sibling tables, not columns here.
 */
@Entity
@Table(name = "password_credentials")
@Getter
@NoArgsConstructor
public class PasswordCredentialEntity extends Auditable {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Setter
  @Column(name = "user_id", nullable = false, unique = true)
  private UUID userId;

  @Setter
  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;
}
