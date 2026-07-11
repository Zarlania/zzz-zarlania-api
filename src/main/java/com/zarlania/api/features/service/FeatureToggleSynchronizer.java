package com.zarlania.api.features.service;

import com.zarlania.api.features.Feature;
import com.zarlania.api.features.entity.FeatureToggleEntity;
import com.zarlania.api.features.repository.FeatureToggleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the {@link Feature} code registry with the {@code feature_toggles} table during
 * application boot: registers new toggles default-off, updates the stored description of existing
 * toggles when the enum's description changes, and deletes rows whose enum constant no longer
 * exists (the DB {@code ON DELETE CASCADE} removes their organization overrides). Descriptions are
 * code-owned — this is the only writer of the column. Runs as an {@link ApplicationRunner} so a
 * failure aborts startup rather than leaving code and DB drifted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeatureToggleSynchronizer implements ApplicationRunner {

  private final FeatureToggleRepository featureToggleRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    synchronize(
        Arrays.stream(Feature.values())
            .collect(Collectors.toMap(Feature::toggleName, Feature::description)));
  }

  /**
   * Brings the {@code feature_toggles} table in line with the given registered toggles.
   *
   * @param registered map of registered toggle name to its code-owned description
   */
  @Transactional
  public void synchronize(Map<String, String> registered) {
    List<FeatureToggleEntity> existing = featureToggleRepository.findAll();
    Map<String, FeatureToggleEntity> byName =
        existing.stream().collect(Collectors.toMap(FeatureToggleEntity::getName, entity -> entity));

    List<FeatureToggleEntity> toSave = new ArrayList<>();

    for (Map.Entry<String, String> entry : registered.entrySet()) {
      FeatureToggleEntity current = byName.get(entry.getKey());
      if (current == null) {
        FeatureToggleEntity created = new FeatureToggleEntity();
        created.setName(entry.getKey());
        created.setPercentage(0);
        created.setDescription(entry.getValue());
        toSave.add(created);
      } else if (!entry.getValue().equals(current.getDescription())) {
        current.setDescription(entry.getValue());
        toSave.add(current);
      }
    }
    featureToggleRepository.saveAll(toSave);

    List<FeatureToggleEntity> orphaned =
        existing.stream().filter(toggle -> !registered.containsKey(toggle.getName())).toList();
    featureToggleRepository.deleteAll(orphaned);
    featureToggleRepository.flush();

    log.info(
        "Feature toggles synchronized: {} registered, {} inserted-or-updated, {} removed",
        registered.size(),
        toSave.size(),
        orphaned.size());
  }
}
