package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class FiscalReleaseBuildProfileContractTest {
    @Test
    void testBundleHasAnIsolatedBuildDirectoryAndNoLegalDeclaration() throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
        assertThat(xpath.evaluate("/project/profiles/profile[id='test-bundle']/build/directory", document))
                .isEqualTo("${project.basedir}/target/test-bundle-build");
        assertThat(xpath.evaluate("/project/profiles/profile[id='test-bundle']/properties/tpv.release.version", document))
                .isEqualTo("0.1.0");
        assertThat(xpath.evaluate("/project/profiles/profile[id='test-bundle']/properties/tpv.release.sequence", document))
                .isEqualTo("38");
        assertThat(Files.readString(Path.of("src/test-bundle/resources/META-INF/tpv-test-bundle.properties")))
                .contains("purpose=TEST_ONLY", "production.allowed=false");
        assertThat(Files.exists(Path.of("src/test-bundle/resources/META-INF/fiscal"))).isFalse();
    }

    @Test
    void sourceDeclaresOneFilteredManifestAndDistinctDevAndReleaseProfiles() throws Exception {
        var manifest = Files.readString(Path.of("src/main/resources/META-INF/tpv-erp-release.properties"),
                StandardCharsets.UTF_8);
        var pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);
        var profilesStart = pom.indexOf("<profiles>");
        assertThat(profilesStart).isPositive();
        var defaultBuild = pom.substring(0, profilesStart);
        var releaseProfiles = pom.substring(profilesStart);
        assertThat(manifest).contains("@tpv.release.id@", "@tpv.release.version@",
                "@tpv.release.capability@").contains("schema.version=V281")
                .contains("release.sequence=@tpv.release.sequence@")
                .contains("build.sequence=@tpv.release.build.sequence@");
        assertThat(defaultBuild).contains("<tpv.release.id>tpv-erp-dev-v281</tpv.release.id>")
                .contains("<tpv.release.version>DEV</tpv.release.version>")
                .contains("<tpv.release.capability>DUAL</tpv.release.capability>")
                .contains("<tpv.release.sequence>38</tpv.release.sequence>")
                .contains("<tpv.release.build.sequence>0</tpv.release.build.sequence>");
        assertThat(releaseProfiles)
                .contains("<id>production-release</id>")
                .contains("<tpv.release.id>tpv-erp-1.0.0-v281</tpv.release.id>")
                .contains("<tpv.release.version>1.0.0</tpv.release.version>")
                .contains("<tpv.release.capability>VERIFACTU_ONLY</tpv.release.capability>")
                .contains("<tpv.release.sequence>39</tpv.release.sequence>")
                .contains("<tpv.release.build.sequence>1</tpv.release.build.sequence>");
        assertThat(pom).contains("<useDefaultDelimiters>false</useDefaultDelimiters>")
                .contains("<delimiter>@</delimiter>");
        assertThat(Files.exists(Path.of("src/main/resources/META-INF/tpv-erp-dev-release.properties")))
                .isFalse();
    }

    @Test
    void packagedManifestMatchesTheLatestVersionedMigration() throws Exception {
        var values = new Properties();
        try (var input = Files.newInputStream(Path.of("target/classes/META-INF/tpv-erp-release.properties"))) {
            values.load(input);
        }
        try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            var latest = migrations.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.contains("__") && name.endsWith(".sql"))
                    .map(name -> MigrationVersion.fromVersion(name.substring(1, name.indexOf("__"))))
                    .max(MigrationVersion::compareTo).orElseThrow();
            assertThat(values.getProperty("schema.version")).isEqualTo("V" + latest);
        }
    }

    @Test
    void filteredManifestContentIsInternallyCoherentWhenBuilt() throws Exception {
        var output = Path.of("target/classes/META-INF/tpv-erp-release.properties");
        if (!Files.isRegularFile(output)) {
            return;
        }
        var values = new Properties();
        try (var input = Files.newInputStream(output)) {
            values.load(input);
        }
        assertThat(values.getProperty("capability")).isIn("DUAL", "VERIFACTU_ONLY");
        if ("DUAL".equals(values.getProperty("capability"))) {
            assertThat(values.getProperty("system.version")).isIn("DEV", "0.1.0");
        } else {
            assertThat(values.getProperty("system.version")).isEqualTo("1.0.0");
        }
    }

    @Test
    void resourceFilteringKeepsRuntimeEnvironmentPlaceholdersWhileResolvingReleaseTokens()
            throws Exception {
        var application = Path.of("target/classes/application.yml");
        if (!Files.isRegularFile(application)) {
            return;
        }
        var content = Files.readString(application, StandardCharsets.UTF_8);
        assertThat(content).contains("${TPV_VERIFACTU_WORKER_ENABLED:false}")
                .contains("${TPV_VERIFACTU_WORKER_DELAY_MS:60000}");
        assertThat(content.contains("system-version: DEV")
                || content.contains("system-version: 1.0.0")
                || content.contains("system-version: 0.1.0")).isTrue();
    }
}
