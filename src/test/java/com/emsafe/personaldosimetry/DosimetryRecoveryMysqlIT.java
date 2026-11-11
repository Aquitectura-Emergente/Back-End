package com.emsafe.personaldosimetry;

class DosimetryRecoveryMysqlIT extends DosimetryRestartRecoveryContract {
    protected String url() { return MysqlTestDatabase.url(); }
    protected String driver() { return "com.mysql.cj.jdbc.Driver"; }
    protected String username() { return MysqlTestDatabase.username(); }
    protected String password() { return MysqlTestDatabase.password(); }
    protected boolean useFlyway() { return true; }
}
