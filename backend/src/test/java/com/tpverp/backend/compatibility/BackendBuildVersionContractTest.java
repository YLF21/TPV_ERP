package com.tpverp.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Properties;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.BuildInfoContributor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;

class BackendBuildVersionContractTest {
    @Test
    void mavenArtifactVersionIsPublishedByCompatibilityAndActuatorInfo() throws Exception {
        var pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of("pom.xml").toFile());
        var expectedVersion = XPathFactory.newInstance().newXPath().evaluate("/project/version", pom);
        var generated = new Properties();
        try (var input = new ClassPathResource("META-INF/build-info.properties").getInputStream()) {
            generated.load(input);
        }
        var values = new Properties();
        generated.forEach((key, value) -> values.put(key.toString().substring("build.".length()), value));
        var build = new BuildProperties(values);
        assertThat(build.getVersion()).isEqualTo(expectedVersion).doesNotContain("@");

        var compatibility = new SystemCompatibilityController(build, "1", "0.0.1").compatibility();
        assertThat(compatibility.backendVersion()).isEqualTo(expectedVersion);
        var info = new Info.Builder();
        new BuildInfoContributor(build).contribute(info);
        var buildDetails = (java.util.Map<?, ?>) info.build().getDetails().get("build");
        assertThat(buildDetails.get("version")).isEqualTo(expectedVersion);
    }
}
