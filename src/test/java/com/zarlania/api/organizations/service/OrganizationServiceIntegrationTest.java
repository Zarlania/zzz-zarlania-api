package com.zarlania.api.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zarlania.api.organizations.MembershipRole;
import com.zarlania.api.organizations.OrganizationType;
import com.zarlania.api.organizations.dto.Membership;
import com.zarlania.api.organizations.dto.Organization;
import com.zarlania.api.organizations.exception.DuplicateMembershipException;
import com.zarlania.api.organizations.exception.OrganizationNameAlreadyExistsException;
import com.zarlania.api.organizations.exception.OrganizationNotFoundException;
import com.zarlania.api.organizations.exception.PersonalOrganizationAlreadyExistsException;
import com.zarlania.api.organizations.exception.PersonalOrganizationMembershipException;
import com.zarlania.api.organizations.support.OrganizationTestSupport;
import com.zarlania.api.persistence.JpaConfig;
import com.zarlania.api.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

// Fast JPA slice for the service over a real DB. The H2 pin and between-test cleanup are inherited
// from AbstractIntegrationTest.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, OrganizationService.class, OrganizationMapper.class})
class OrganizationServiceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OrganizationService service;
  @Autowired private TestEntityManager entityManager;

  // Local binder over the shared seed helper (the SQL lives once in OrganizationTestSupport), so
  // the
  // memberships.user_id FK is satisfied without importing the users domain.
  private UUID seedUser(String email) {
    return OrganizationTestSupport.seedUser(entityManager.getEntityManager(), email);
  }

  @Test
  void createPersonalOrganizationCreatesOrgWithItsSingleOwner() {
    UUID owner = seedUser("owner@example.com");

    Organization org = service.createPersonalOrganization(owner, "Owner's Space");

    assertThat(org.id()).isNotNull();
    assertThat(org.type()).isEqualTo(OrganizationType.PERSONAL);
    assertThat(service.findMemberships(org.id()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.userId()).isEqualTo(owner);
              assertThat(m.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void createPersonalOrganizationRejectsSecondForSameOwner() {
    UUID owner = seedUser("owner@example.com");
    service.createPersonalOrganization(owner, "First");

    assertThatThrownBy(() -> service.createPersonalOrganization(owner, "Second"))
        .isInstanceOf(PersonalOrganizationAlreadyExistsException.class);
  }

  @Test
  void differentOwnersMayEachHavePersonalOrganization() {
    UUID first = seedUser("first@example.com");
    UUID second = seedUser("second@example.com");

    service.createPersonalOrganization(first, "First Space");
    Organization secondOrg = service.createPersonalOrganization(second, "Second Space");

    assertThat(secondOrg.id()).isNotNull();
  }

  @Test
  void createGeneralOrganizationCreatesOrgWithCreatorAsOwner() {
    UUID creator = seedUser("creator@example.com");

    Organization org = service.createGeneralOrganization(creator, "Acme");

    assertThat(org.type()).isEqualTo(OrganizationType.GENERAL);
    assertThat(service.findMemberships(org.id()))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.userId()).isEqualTo(creator);
              assertThat(m.role()).isEqualTo(MembershipRole.OWNER);
            });
  }

  @Test
  void createGeneralOrganizationRejectsDuplicateName() {
    UUID first = seedUser("first@example.com");
    UUID second = seedUser("second@example.com");
    service.createGeneralOrganization(first, "Acme");

    assertThatThrownBy(() -> service.createGeneralOrganization(second, "Acme"))
        .isInstanceOf(OrganizationNameAlreadyExistsException.class);
  }

  @Test
  void organizationNamesAreUniqueAcrossTypes() {
    UUID general = seedUser("general@example.com");
    UUID personal = seedUser("personal@example.com");
    service.createGeneralOrganization(general, "Shared");

    assertThatThrownBy(() -> service.createPersonalOrganization(personal, "Shared"))
        .isInstanceOf(OrganizationNameAlreadyExistsException.class);
  }

  @Test
  void addMemberAddsNonOwnerToGeneralOrganization() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    Membership membership = service.addMember(org.id(), member);

    assertThat(membership.role()).isEqualTo(MembershipRole.MEMBER);
    assertThat(service.findMemberships(org.id())).hasSize(2);
  }

  @Test
  void addMemberIsRejectedForPersonalOrganization() {
    UUID owner = seedUser("owner@example.com");
    UUID other = seedUser("other@example.com");
    Organization personal = service.createPersonalOrganization(owner, "Mine");

    assertThatThrownBy(() -> service.addMember(personal.id(), other))
        .isInstanceOf(PersonalOrganizationMembershipException.class);
  }

  @Test
  void addMemberIsRejectedForDuplicateMembership() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");
    service.addMember(org.id(), member);

    assertThatThrownBy(() -> service.addMember(org.id(), member))
        .isInstanceOf(DuplicateMembershipException.class);
  }

  @Test
  void addMemberIsRejectedForAnUnknownOrganization() {
    UUID member = seedUser("member@example.com");

    assertThatThrownBy(() -> service.addMember(UUID.randomUUID(), member))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void addOwnerIsRejectedForAnUnknownOrganization() {
    UUID userId = seedUser("ghost@example.com");

    assertThatThrownBy(() -> service.addOwner(UUID.randomUUID(), userId))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void addOwnerAddsNewOwnerToGeneralOrganization() {
    UUID creator = seedUser("creator@example.com");
    UUID coOwner = seedUser("coowner@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    Membership membership = service.addOwner(org.id(), coOwner);

    assertThat(membership.role()).isEqualTo(MembershipRole.OWNER);
    assertThat(service.findMemberships(org.id()))
        .filteredOn(m -> m.role() == MembershipRole.OWNER)
        .hasSize(2);
  }

  @Test
  void addOwnerPromotesExistingMemberWithoutCreatingSecondMembership() {
    UUID creator = seedUser("creator@example.com");
    UUID member = seedUser("member@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");
    service.addMember(org.id(), member);

    Membership promoted = service.addOwner(org.id(), member);

    assertThat(promoted.role()).isEqualTo(MembershipRole.OWNER);
    // creator (owner) + the now-promoted member: still exactly two memberships, no duplicate row.
    List<Membership> all = service.findMemberships(org.id());
    assertThat(all).hasSize(2);
    assertThat(all).filteredOn(m -> m.userId().equals(member)).hasSize(1);
  }

  @Test
  void addOwnerIsRejectedForPersonalOrganization() {
    UUID owner = seedUser("owner@example.com");
    UUID other = seedUser("other@example.com");
    Organization personal = service.createPersonalOrganization(owner, "Mine");

    assertThatThrownBy(() -> service.addOwner(personal.id(), other))
        .isInstanceOf(PersonalOrganizationMembershipException.class);
  }

  @Test
  void generalOrganizationMayHaveMultipleOwnersAndMembers() {
    UUID creator = seedUser("creator@example.com");
    UUID secondOwner = seedUser("owner2@example.com");
    UUID firstMember = seedUser("member1@example.com");
    UUID secondMember = seedUser("member2@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    service.addOwner(org.id(), secondOwner);
    service.addMember(org.id(), firstMember);
    service.addMember(org.id(), secondMember);

    List<Membership> all = service.findMemberships(org.id());
    assertThat(all).filteredOn(m -> m.role() == MembershipRole.OWNER).hasSize(2);
    assertThat(all).filteredOn(m -> m.role() == MembershipRole.MEMBER).hasSize(2);
  }

  @Test
  void findByIdReturnsTheCreatedOrganization() {
    UUID creator = seedUser("creator@example.com");
    Organization org = service.createGeneralOrganization(creator, "Acme");

    assertThat(service.findById(org.id())).contains(org);
  }

  @Test
  void findByIdIsEmptyForAnUnknownOrganization() {
    assertThat(service.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findsThePersonalOrganizationForItsOwner() {
    UUID owner = seedUser("owner@example.com");
    Organization created = service.createPersonalOrganization(owner, "own-name");

    assertThat(service.findPersonalOrganization(owner))
        .hasValueSatisfying(
            found -> {
              assertThat(found.id()).isEqualTo(created.id());
              assertThat(found.type()).isEqualTo(OrganizationType.PERSONAL);
            });
  }

  @Test
  void personalOrganizationLookupIsEmptyForUserWithoutOne() {
    UUID owner = seedUser("owner@example.com");
    assertThat(service.findPersonalOrganization(owner)).isEmpty();
  }

  @Test
  void personalOrganizationLookupIgnoresGeneralOrganizations() {
    UUID owner = seedUser("owner@example.com");
    service.createGeneralOrganization(owner, "gen-" + owner);
    assertThat(service.findPersonalOrganization(owner)).isEmpty();
  }
}
