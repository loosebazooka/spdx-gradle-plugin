/*
 * Copyright 2023 The Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spdx.sbom.gradle.utils;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.internal.artifacts.result.ResolvedComponentResultInternal;
import org.gradle.api.logging.Logger;
import org.gradle.process.ExecOperations;
import org.spdx.library.InvalidSPDXAnalysisException;
import org.spdx.library.ModelCopyManager;
import org.spdx.library.model.ReferenceType;
import org.spdx.library.model.SpdxDocument;
import org.spdx.library.model.SpdxModelFactory;
import org.spdx.library.model.SpdxPackage;
import org.spdx.library.model.SpdxPackage.SpdxPackageBuilder;
import org.spdx.library.model.enumerations.ChecksumAlgorithm;
import org.spdx.library.model.enumerations.ReferenceCategory;
import org.spdx.library.model.enumerations.RelationshipType;
import org.spdx.library.model.license.SpdxNoAssertionLicense;
import org.spdx.sbom.gradle.extensions.SpdxSbomTaskExtension;
import org.spdx.sbom.gradle.maven.PomInfo;
import org.spdx.sbom.gradle.project.DocumentInfo;
import org.spdx.sbom.gradle.project.ProjectInfo;
import org.spdx.sbom.gradle.project.ScmInfo;
import org.spdx.storage.IModelStore;
import org.spdx.storage.IModelStore.IdType;

public class SpdxDocumentBuilder {
  private final SpdxDocument doc;
  private final SpdxPackage rootPackage;
  private final SpdxLicenses licenses;
  private final Map<String, ProjectInfo> knownProjects;
  private final HashMap<ComponentIdentifier, SpdxPackage> spdxPackages = new HashMap<>();

  private final HashMap<ComponentIdentifier, LinkedHashSet<ComponentIdentifier>> tree =
      new LinkedHashMap<>();
  private final Map<ComponentIdentifier, File> resolvedArtifacts;
  private final Map<String, URI> mavenArtifactRepositories;
  private final Map<String, PomInfo> poms;
  private final Logger logger;

  @Nullable private final SpdxSbomTaskExtension taskExtension;
  private final ScmInfo scmInfo;

  public SpdxDocumentBuilder(
      Set<ProjectInfo> allProjects,
      ExecOperations execOperations,
      Logger logger,
      IModelStore modelStore,
      Map<ComponentArtifactIdentifier, File> resolvedArtifacts,
      Map<String, URI> mavenArtifactRepositories,
      Map<String, PomInfo> poms,
      SpdxSbomTaskExtension spdxSbomTaskExtension,
      DocumentInfo documentInfo,
      ScmInfo scmInfo)
      throws InvalidSPDXAnalysisException, IOException, InterruptedException {

    doc =
        SpdxModelFactory.createSpdxDocument(
            modelStore, documentInfo.getNamespace(), new ModelCopyManager());
    doc.setName(documentInfo.getName());

    ImmutableList.Builder<String> creators = ImmutableList.builder();
    creators.add("Tool: spdx-gradle-plugin");
    documentInfo.getCreator().ifPresent(creators::add);

    doc.setCreationInfo(
        doc.createCreationInfo(
            creators.build(),
            ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_DATE_TIME)));
    if (documentInfo.getRootPackage().isPresent()) {
      var rootPackageInfo = documentInfo.getRootPackage().get();
      this.rootPackage =
          doc.createPackage(
                  doc.getModelStore().getNextId(IdType.SpdxId, doc.getDocumentUri()),
                  rootPackageInfo.getName(),
                  new SpdxNoAssertionLicense(),
                  "NOASSERTION",
                  new SpdxNoAssertionLicense())
              .setSupplier(rootPackageInfo.getSupplier())
              .setVersionInfo(rootPackageInfo.getVersion())
              .setFilesAnalyzed(false)
              .build();
      doc.setDocumentDescribes(Collections.singletonList(this.rootPackage));
    } else {
      rootPackage = null;
    }

    this.licenses = SpdxLicenses.newSpdxLicenes(logger, doc);

    this.logger = logger;
    this.scmInfo = scmInfo;
    this.knownProjects =
        allProjects.stream().collect(Collectors.toMap(ProjectInfo::getPath, Function.identity()));

    this.resolvedArtifacts =
        resolvedArtifacts
            .entrySet()
            .stream()
            .collect(Collectors.toMap(e -> e.getKey().getComponentIdentifier(), Entry::getValue));
    this.mavenArtifactRepositories = mavenArtifactRepositories;
    this.poms = poms;

    this.taskExtension = spdxSbomTaskExtension;
  }

  public void add(ResolvedComponentResult root) throws InvalidSPDXAnalysisException, IOException {
    add(null, root, new HashSet<>());
    if (rootPackage == null) {
      doc.setDocumentDescribes(Collections.singletonList(spdxPackages.get(root.getId())));
    } else {
      doc.setDocumentDescribes(Collections.singletonList(rootPackage));
      rootPackage.addRelationship(
          doc.createRelationship(
              spdxPackages.get(root.getId()), RelationshipType.DEPENDS_ON, null));
    }
    for (var pkg : tree.keySet()) {
      for (var child : tree.get(pkg)) {
        var rel =
            doc.createRelationship(spdxPackages.get(child), RelationshipType.DEPENDS_ON, null);
        spdxPackages.get(pkg).addRelationship(rel);
      }
    }
  }

  private void add(
      ResolvedComponentResult parent,
      ResolvedComponentResult component,
      Set<ComponentIdentifier> visited)
      throws InvalidSPDXAnalysisException, IOException {
    if (visited.contains(component.getId())) {
      return;
    }
    if (!spdxPackages.containsKey(component.getId())) {
      SpdxPackage pkg;
      if (component.getId() instanceof ProjectComponentIdentifier) {
        pkg = createProjectPackage(component);
      } else if (component.getId() instanceof ModuleComponentIdentifier) {
        var result = createMavenModulePackage(component);
        if (result.isEmpty()) {
          logger.info("ignoring: " + component.getId());
          return; // ignore this package (maybe it's a bom?)
        }
        pkg = result.get();
      } else {
        throw new RuntimeException(
            "Unknown package type: "
                + component.getClass().getName()
                + " "
                + component.getId().getClass().getName()
                + " "
                + component.getId());
      }
      spdxPackages.put(component.getId(), pkg);
    }
    tree.putIfAbsent(component.getId(), new LinkedHashSet<>());
    if (parent != null) {
      tree.get(parent.getId()).add(component.getId());
    }
    visited.add(component.getId());

    for (var child : component.getDependencies()) {
      if (child instanceof ResolvedDependencyResult) {
        add(component, ((ResolvedDependencyResult) child).getSelected(), visited);
      }
    }
  }

  private SpdxPackage createProjectPackage(ResolvedComponentResult resolvedComponentResult)
      throws InvalidSPDXAnalysisException {
    var projectId = (ProjectComponentIdentifier) resolvedComponentResult.getId();

    resolvedComponentResult.getVariants();
    ProjectInfo pi = knownProjects.get(projectId.getProjectPath());
    SpdxPackageBuilder builder =
        doc.createPackage(
                doc.getModelStore().getNextId(IdType.SpdxId, doc.getDocumentUri()),
                pi.getName(),
                new SpdxNoAssertionLicense(),
                "NOASSERTION",
                new SpdxNoAssertionLicense())
            .setFilesAnalyzed(false)
            .setDescription("" + pi.getDescription().orElse(""))
            .setVersionInfo("" + pi.getVersion())
            .setDownloadLocation("NOASSERTION");

    if (taskExtension != null) {
      builder.setSourceInfo(taskExtension.mapScmForProject(scmInfo, pi).getSourceInfo(pi));
    } else {
      builder.setSourceInfo(scmInfo.getSourceInfo(pi));
    }
    return builder.build();
  }

  private Optional<SpdxPackage> createMavenModulePackage(
      ResolvedComponentResult resolvedComponentResult)
      throws InvalidSPDXAnalysisException, IOException {

    // if the project doesn't resolve to anything, ignore it
    File dependencyFile = resolvedArtifacts.get(resolvedComponentResult.getId());
    if (dependencyFile != null) {

      ModuleVersionIdentifier moduleId = resolvedComponentResult.getModuleVersion();

      PomInfo pomInfo = poms.get(resolvedComponentResult.getId().getDisplayName());

      SpdxPackageBuilder spdxPkgBuilder =
          doc.createPackage(
              doc.getModelStore().getNextId(IdType.SpdxId, doc.getDocumentUri()),
              moduleId.getGroup() + ":" + moduleId.getName() + ":" + moduleId.getVersion(),
              new SpdxNoAssertionLicense(),
              "NOASSERTION",
              licenses.asSpdxLicense(pomInfo.getLicenses()));

      String sourceRepo =
          ((ResolvedComponentResultInternal) resolvedComponentResult).getRepositoryName();
      if (sourceRepo == null) {
        throw new RuntimeException("Source repo was null?");
      }

      var baseURI = mavenArtifactRepositories.get(sourceRepo);
      String modulePath =
          moduleId.getGroup().replace(".", "/")
              + "/"
              + moduleId.getName()
              + "/"
              + moduleId.getVersion()
              + "/"
              + URLEncoder.encode(dependencyFile.getName(), StandardCharsets.UTF_8);
      URI downloadLocation = baseURI.resolve(modulePath);
      if (taskExtension != null) {
        downloadLocation = taskExtension.mapDownloadUri(downloadLocation);
      }
      spdxPkgBuilder.setDownloadLocation(downloadLocation.toString());

      String sha256 =
          com.google.common.io.Files.asByteSource(dependencyFile).hash(Hashing.sha256()).toString();
      var checksum = doc.createChecksum(ChecksumAlgorithm.SHA256, sha256);
      spdxPkgBuilder.setChecksums(Collections.singletonList(checksum));
      spdxPkgBuilder.setFilesAnalyzed(false);

      var externalRef =
          doc.createExternalRef(
              ReferenceCategory.PACKAGE_MANAGER,
              new ReferenceType("purl"),
              "pkg://maven/"
                  + moduleId.getGroup()
                  + "/"
                  + moduleId.getName()
                  + "@"
                  + moduleId.getVersion(),
              null);
      spdxPkgBuilder.setExternalRefs(Collections.singletonList(externalRef));

      return Optional.of(spdxPkgBuilder.build());
    }
    return Optional.empty();
  }

  public SpdxDocument getSpdxDocument() {
    return doc;
  }
}
