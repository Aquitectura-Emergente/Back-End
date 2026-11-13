package com.emsafe.devicemanagement.infrastructure.mqtt.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Las fixtures son copias de los ejemplos de la sección "Contrato MQTT" de los
 * README de los Edges. Si los repos de los Edges están al lado de este, se
 * comprueba que sigan iguales; si no (CI de este repo), el test se omite.
 */
class ContractFixturesMatchReadmeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void industrialExamples() throws Exception {
        compare(Path.of("../emsafe-edge-industrial/README.md"),
                List.of("telemetry", "device-status", "device-config", "edge-online", "edge-lwt"), true);
    }

    @Test
    void homeExamples() throws Exception {
        compare(Path.of("../emsafe-edge-flask/README.md"),
                List.of("telemetry", "device-status", "edge-online", "edge-lwt", "plug-command", "plug-state"), false);
    }

    private void compare(Path readme, List<String> names, boolean industrial) throws Exception {
        assumeTrue(Files.exists(readme), "README del Edge no disponible: " + readme);
        List<String> blocks = contractBlocks(Files.readString(readme));
        assertThat(blocks).hasSize(names.size());
        for (int i = 0; i < names.size(); i++) {
            String fixture = industrial ? ContractFixtures.industrial(names.get(i)) : ContractFixtures.home(names.get(i));
            assertThat(JSON.readTree(fixture)).as(readme + " → " + names.get(i)).isEqualTo(JSON.readTree(blocks.get(i)));
        }
    }

    private static List<String> contractBlocks(String readme) {
        String section = readme.substring(readme.indexOf("## Contrato MQTT"));
        Matcher next = Pattern.compile("\n## (?!#)").matcher(section);
        if (next.find(5)) section = section.substring(0, next.start());
        List<String> blocks = new ArrayList<>();
        Matcher m = Pattern.compile("```json\\r?\\n(.*?)```", Pattern.DOTALL).matcher(section);
        while (m.find()) blocks.add(m.group(1));
        return blocks;
    }
}
