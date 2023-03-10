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
package org.spdx;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.gradle.testkit.runner.GradleRunner;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A simple functional test for the 'org.spdx.greeting' plugin. */
class SpdxSbomPluginFunctionalTest {
  @TempDir File projectDir;

  private File getBuildFile() {
    return new File(projectDir, "build.gradle");
  }

  private File getKotlinBuildFile() {
    return new File(projectDir, "build.gradle.kts");
  }

  private File getSettingsFile() {
    return new File(projectDir, "settings.gradle");
  }

  private File getKotlinSettingsFile() {
    return new File(projectDir, "settings.gradle.kts");
  }

  @Test
  void canRunTask() throws IOException {
    writeString(
        getSettingsFile(),
        "rootProject.name = 'spdx-functional-test-project'\n" + "include 'sub-project'");
    writeString(
        getBuildFile(),
        "plugins {\n"
            + "  id('org.spdx.sbom')\n"
            + "  id('java')\n"
            + "}\n"
            + "repositories {\n"
            + "  google()\n"
            + "  mavenCentral()\n"
            + "}\n"
            + "dependencies {\n"
            + "  implementation 'android.arch.persistence:db:1.1.1'\n"
            + "  implementation 'dev.sigstore:sigstore-java:0.3.0'\n"
            + "  implementation project(':sub-project')\n"
            + "}\n"
            + "spdxSbom {\n"
            + "  targets {\n"
            + "    release {\n"
            + "      configuration = 'testCompileClasspath'\n"
            + "    }\n"
            + "  }\n"
            + "}\n");

    Path main = projectDir.toPath().resolve(Paths.get("src/main/java/main/Main.java"));
    Files.createDirectories(main.getParent());
    writeString(
        Files.createFile(main).toFile(),
        "package main;\n"
            + "import lib.Lib;\n"
            + "public class Main {\n"
            + "  public static void main(String[] args) { Lib.doSomething(); }\n"
            + "}");

    Path resource = projectDir.toPath().resolve(Paths.get("src/main/resources/res.txt"));
    Files.createDirectories(resource.getParent());
    writeString(Files.createFile(resource).toFile(), "duck duck duck, goose");

    Path sub = projectDir.toPath().resolve("sub-project");
    Files.createDirectories(sub);
    writeString(sub.resolve("build.gradle").toFile(), "plugins {\n" + "  id('java')\n" + "}\n");

    Path lib = sub.resolve(Paths.get("src/main/java/lib/Lib.java"));
    Files.createDirectories(lib.getParent());
    writeString(
        Files.createFile(lib).toFile(),
        "package lib;\n" + "public class Lib { public static int doSomething() { return 5; } }\n");

    // Run the build
    GradleRunner runner = GradleRunner.create();
    runner.forwardOutput();
    runner.withPluginClasspath();
    runner.withDebug(true);
    runner.withArguments("spdxSbomForRelease", "--stacktrace");
    runner.withProjectDir(projectDir);
    runner.build();

    Path outputFile = projectDir.toPath().resolve(Paths.get("build/spdx/release.spdx.json"));

    // Verify the result
    assertTrue(Files.isRegularFile(outputFile));

    System.out.println(Files.readString(outputFile));
  }

  @Test
  public void canRunOnPluginProject() throws IOException {
    writeString(getKotlinSettingsFile(), "rootProject.name = \"spdx-functional-test-project\"");
    writeString(
        getKotlinBuildFile(),
        "plugins {\n"
            + "  id(\"org.spdx.sbom\")\n"
            + "  `java-gradle-plugin`\n"
            + "}\n"
            + "repositories {\n"
            + "  google()\n"
            + "  mavenCentral()\n"
            + "}\n"
            + "dependencies {\n"
            + "  implementation(\"dev.sigstore:sigstore-java:0.3.0\")\n"
            + "}\n"
            + "spdxSbom {\n"
            + "  targets {\n"
            + "    create(\"sbom\") {\n"
            + "    }\n"
            + "    create(\"test\") {\n"
            + "      configuration.set(\"testRuntimeClasspath\")\n"
            + "    }\n"
            + "  }\n"
            + "}\n");

    GradleRunner runner = GradleRunner.create();
    runner.forwardOutput();
    runner.withPluginClasspath();
    runner.withDebug(true);
    runner.withArguments("spdxSbom", "--stacktrace");
    runner.withProjectDir(projectDir);
    runner.build();

    Path outputFile = projectDir.toPath().resolve(Paths.get("build/spdx/sbom.spdx.json"));
    Path outputFile2 = projectDir.toPath().resolve(Paths.get("build/spdx/test.spdx.json"));

    // Verify the result
    assertTrue(Files.isRegularFile(outputFile));
    assertTrue(Files.isRegularFile(outputFile2));
  }

  @Test
  public void canUseBuildExtension() throws IOException {
    writeString(getKotlinSettingsFile(), "rootProject.name = \"spdx-functional-test-project\"");
    writeString(
        getKotlinBuildFile(),
        "import java.net.URI\n"
            + "plugins {\n"
            + "  id(\"org.spdx.sbom\")\n"
            + "  `java`\n"
            + "}\n"
            + "tasks.withType(org.spdx.sbom.gradle.SpdxSbomTask::class.java) {\n"
            + "  this.taskExtension.set(org.spdx.sbom.gradle.extensions.SpdxSbomTaskExtension { URI.create(\"https://duck.com\") })\n"
            + "}\n"
            + "repositories {\n"
            + "  mavenCentral()\n"
            + "}\n"
            + "dependencies {\n"
            + "  implementation(\"dev.sigstore:sigstore-java:0.3.0\")\n"
            + "}\n"
            + "spdxSbom {\n"
            + "  targets {\n"
            + "    create(\"sbom\") {\n"
            + "    }\n"
            + "  }\n"
            + "}\n");

    GradleRunner runner = GradleRunner.create();
    runner.forwardOutput();
    runner.withPluginClasspath();
    runner.withDebug(true);
    runner.withArguments("spdxSbom", "--stacktrace");
    runner.withProjectDir(projectDir);
    runner.build();

    Path outputFile = projectDir.toPath().resolve(Paths.get("build/spdx/sbom.spdx.json"));

    // Verify the result
    assertTrue(Files.isRegularFile(outputFile));
    Files.readAllLines(outputFile)
        .stream()
        .filter(line -> line.contains("downloadLocation"))
        .filter(line -> !line.contains("NOASSERTION"))
        .forEach(
            line -> MatcherAssert.assertThat(line, Matchers.containsString("https://duck.com")));
  }

  private void writeString(File file, String string) throws IOException {
    try (Writer writer = new FileWriter(file)) {
      writer.write(string);
    }
  }
}
