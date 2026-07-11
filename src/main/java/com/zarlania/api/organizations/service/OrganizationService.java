package com.zarlania.api.organizations.service;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.entity.MembershipEntity;
import com.zarlania.api.organizations.entity.OrganizationEntity;
import com.zarlania.api.organizations.exception.DuplicateMembershipException;
import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
import com.zarlania.api.organizations.exception.PersonalOrganizationMembershipException;
import com.zarlania.api.organizations.repository.MembershipRepository;
import com.zarlania.api.organizations.repository.OrganizationRepository;
import com.zarlania.api.persistence.ConstraintViolations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates organizations and manages their memberships, enforcing every ownership invariant. The
 * public surface of the {@code organizations} domain. References users only by opaque id — it never
 * loads the {@code users} domain (ADR-0011).
 */
@Service
@RequiredArgsConstructor
public class OrganizationService {

  /** Name of the organization-name unique constraint in {@code V2__...sql}. */
  private static final String NAME_UNIQUE_CONSTRAINT = "uq_organizations_name";

  /** Name of the (organization_id, user_id) membership unique constraint in {@code V2__...sql}. */
  private static final String MEMBERSHIP_UNIQUE_CONSTRAINT = "uq_memberships_org_user";

  private final OrganizationRepository organizationRepository;
  private final MembershipRepository membershipRepository;
  private final OrganizationMapper organizationMapper;

  /**
   * Creates a user's personal organization and its single owner membership. Enforces the uniqueness
   * half of the 1:1 rule: rejects a second personal organization for an owner who already has one.
   *
   * @param ownerUserId the owning user's id
   * @param name the organization's display name
   * @return the created organization
   * @throws IllegalArgumentException if {@code ownerUserId} is null or {@code name} is blank
   * @throws PersonalOrganizationAlreadyExistsException if the owner already has a personal
   *     organization
   * @throws OrganizationNameAlreadyExistsException if the name is already taken
   */
  @Transactional
  public Organization createPersonalOrganization(UUID ownerUserId, String name) {
    requireNonNull(ownerUserId, "ownerUserId");
    requireNonBlank(name, "name");
    if (membershipRepository.existsByUserIdAndRoleAndOrganizationType(
        ownerUserId, MembershipRole.OWNER, OrganizationType.PERSONAL)) {
      throw PersonalOrganizationAlreadyExistsException.forOwner(ownerUserId);
    }
    OrganizationEntity organization = saveOrganization(name, OrganizationType.PERSONAL);
    addMembership(organization, ownerUserId, MembershipRole.OWNER);
    return organizationMapper.toDto(organization);
  }

  /**
   * Creates a general (company) organization with the creator as its first owner.
   *
   * @param creatorUserId the creating user's id
   * @param name the organization's display name
   * @return the created organization
   * @throws IllegalArgumentException if {@code creatorUserId} is null or {@code name} is blank
   * @throws OrganizationNameAlreadyExistsException if the name is already taken
   */
  @Transactional
  public Organization createGeneralOrganization(UUID creatorUserId, String name) {
    requireNonNull(creatorUserId, "creatorUserId");
    requireNonBlank(name, "name");
    OrganizationEntity organization = saveOrganization(name, OrganizationType.GENERAL);
    addMembership(organization, creatorUserId, MembershipRole.OWNER);
    return organizationMapper.toDto(organization);
  }

  /**
   * Adds a non-owner member to a general organization.
   *
   * @param organizationId the organization to add to
   * @param userId the user to add
   * @return the created membership
   * @throws IllegalArgumentException if either id is null
   * @throws OrganizationNotFoundException if no organization has that id
   * @throws PersonalOrganizationMembershipException if the organization is personal
   * @throws DuplicateMembershipException if the user already belongs to the organization
   */
  @Transactional
  public Membership addMember(UUID organizationId, UUID userId) {
    requireNonNull(organizationId, "organizationId");
    requireNonNull(userId, "userId");
    OrganizationEntity organization = requireGeneralOrganization(organizationId);
    if (membershipRepository.existsByOrganizationIdAndUserId(organizationId, userId)) {
      throw DuplicateMembershipException.forMembership(organizationId, userId);
    }
    return organizationMapper.toDto(addMembership(organization, userId, MembershipRole.MEMBER));
  }

  /**
   * Adds the user to a general organization as an owner, promoting an existing membership if one is
   * present (so no duplicate membership is created).
   *
   * @param organizationId the organization to add to
   * @param userId the user to make an owner
   * @return the owner membership
   * @throws IllegalArgumentException if either id is null
   * @throws OrganizationNotFoundException if no organization has that id
   * @throws PersonalOrganizationMembershipException if the organization is personal
   * @throws DuplicateMembershipException if a concurrent request already added the user
   */
  @Transactional
  public Membership addOwner(UUID organizationId, UUID userId) {
    requireNonNull(organizationId, "organizationId");
    requireNonNull(userId, "userId");
    OrganizationEntity organization = requireGeneralOrganization(organizationId);
    MembershipEntity membership =
        membershipRepository
            .findByOrganizationIdAndUserId(organizationId, userId)
            .orElseGet(
                () -> {
                  MembershipEntity created = new MembershipEntity();
                  created.setOrganization(organization);
                  created.setUserId(userId);
                  return created;
                });
    membership.setRole(MembershipRole.OWNER);
    return organizationMapper.toDto(saveMembership(membership, organizationId, userId));
  }

  /**
   * Finds an organization by id.
   *
   * @param id the organization id
   * @return the organization as a DTO, if found
   */
  @Transactional(readOnly = true)
  public Optional<Organization> findById(UUID id) {
    return organizationRepository.findById(id).map(organizationMapper::toDto);
  }

  /**
   * Lists an organization's memberships.
   *
   * @param organizationId the organization id
   * @return the memberships as DTOs (empty if the organization has none or does not exist)
   */
  @Transactional(readOnly = true)
  public List<Membership> findMemberships(UUID organizationId) {
    return membershipRepository.findByOrganizationId(organizationId).stream()
        .map(organizationMapper::toDto)
        .toList();
  }

  /**
   * Finds the user's personal organization — the organization every issued token is scoped to at
   * login while personal organizations are each user's only organization.
   *
   * @param ownerUserId the owning user's id
   * @return the personal organization as a DTO, if one exists
   */
  @Transactional(readOnly = true)
  public Optional<Organization> findPersonalOrganization(UUID ownerUserId) {
    return membershipRepository
        .findFirstByUserIdAndRoleAndOrganizationType(
            ownerUserId, MembershipRole.OWNER, OrganizationType.PERSONAL)
        .map(MembershipEntity::getOrganization)
        .map(organizationMapper::toDto);
  }

  private OrganizationEntity requireGeneralOrganization(UUID organizationId) {
    OrganizationEntity organization =
        organizationRepository
            .findById(organizationId)
            .orElseThrow(() -> OrganizationNotFoundException.forId(organizationId));
    if (organization.getType() == OrganizationType.PERSONAL) {
      throw PersonalOrganizationMembershipException.forOrganization(organizationId);
    }
    return organization;
  }

  private OrganizationEntity saveOrganization(String name, OrganizationType type) {
    OrganizationEntity organization = new OrganizationEntity();
    organization.setName(name);
    organization.setType(type);
    try {
      // saveAndFlush forces the INSERT now so a name collision that slipped past any pre-check
      // surfaces here as the name unique-constraint violation rather than at commit time.
      return organizationRepository.saveAndFlush(organization);
    } catch (DataIntegrityViolationException ex) {
      if (ConstraintViolations.matches(ex, NAME_UNIQUE_CONSTRAINT)) {
        throw OrganizationNameAlreadyExistsException.forName(name);
      }
      throw ex;
    }
  }

  private MembershipEntity addMembership(
      OrganizationEntity organization, UUID userId, MembershipRole role) {
    MembershipEntity membership = new MembershipEntity();
    membership.setOrganization(organization);
    membership.setUserId(userId);
    membership.setRole(role);
    return saveMembership(membership, organization.getId(), userId);
  }

  private MembershipEntity saveMembership(
      MembershipEntity membership, UUID organizationId, UUID userId) {
    try {
      // saveAndFlush forces the INSERT now so a concurrent duplicate that slipped past the
      // existence pre-check surfaces here as the (organization_id, user_id) unique-constraint
      // violation and is reported as the domain exception rather than a raw persistence error.
      return membershipRepository.saveAndFlush(membership);
    } catch (DataIntegrityViolationException ex) {
      if (ConstraintViolations.matches(ex, MEMBERSHIP_UNIQUE_CONSTRAINT)) {
        throw DuplicateMembershipException.forMembership(organizationId, userId);
      }
      throw ex;
    }
  }

  private static void requireNonNull(UUID value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
