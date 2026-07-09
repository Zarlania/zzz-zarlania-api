package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the {@link Feature} code registry with the {@code feature_toggles} table during
 * application boot: registers new toggles default-off and deletes rows whose enum constant no
 * longer exists (the DB {@code ON DELETE CASCADE} removes their organization overrides). Runs as an
 * {@link ApplicationRunner} so a failure aborts startup rather than leaving code and DB drifted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureToggleSynchronizer implements ApplicationRunner {

  private final FeatureToggleRepository toggleRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    synchronize(Arrays.stream(Feature.values()).map(Enum::name).collect(Collectors.toSet()));
  }

  /**
   * Brings the {@code feature_toggles} table in line with the given registered toggle names.
   *
   * @param registeredNames the toggle names that exist in code
   */
  @Transactional
  public void synchronize(Set<String> registeredNames) {
    List<FeatureToggleEntity> existing = toggleRepository.findAll();
    Set<String> existingNames =
        existing.stream().map(FeatureToggleEntity::getName).collect(Collectors.toSet());

    List<FeatureToggleEntity> created =
        registeredNames.stream()
            .filter(name -> !existingNames.contains(name))
            .map(
                name -> {
                  FeatureToggleEntity toggle = new FeatureToggleEntity();
                  toggle.setName(name);
                  toggle.setPercentage(0);
                  return toggle;
                })
            .toList();
    toggleRepository.saveAll(created);

    List<FeatureToggleEntity> orphaned =
        existing.stream().filter(toggle -> !registeredNames.contains(toggle.getName())).toList();
    toggleRepository.deleteAll(orphaned);
    toggleRepository.flush();

    log.info(
        "Feature toggles synchronized: {} registered, {} created (off), {} removed",
        registeredNames.size(),
        created.size(),
        orphaned.size());
  }
}
