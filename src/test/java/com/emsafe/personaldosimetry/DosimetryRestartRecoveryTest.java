package com.emsafe.personaldosimetry;

import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class DosimetryRestartRecoveryTest extends DosimetryRestartRecoveryContract {
    @TempDir Path databaseDirectory;
    protected String url() {
        return "jdbc:h2:file:" + databaseDirectory.resolve("dosimetry").toAbsolutePath().toString().replace('\\', '/')
                + ";MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_ON_EXIT=FALSE";
    }
    protected String driver() { return "org.h2.Driver"; }
}
