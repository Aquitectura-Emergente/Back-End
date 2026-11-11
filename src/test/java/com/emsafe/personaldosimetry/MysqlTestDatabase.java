package com.emsafe.personaldosimetry;

import java.net.URI;

final class MysqlTestDatabase {
    private MysqlTestDatabase() {}

    static String url() {
        return validateUrl(System.getenv("DOSIMETRY_MYSQL_URL"));
    }

    static String validateUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("Set DOSIMETRY_MYSQL_URL to a dedicated emsafe_pd_test database");
        }
        var uri = URI.create(url.substring("jdbc:".length()));
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getPath() == null
                || !uri.getPath().matches("/emsafe_pd_test(?:_[a-zA-Z0-9]+)?")) {
            throw new IllegalStateException("MySQL tests only accept database names emsafe_pd_test or emsafe_pd_test_<suffix>");
        }
        return url;
    }

    static String username() {
        return System.getenv().getOrDefault("DOSIMETRY_MYSQL_USERNAME", "root");
    }

    static String password() {
        return System.getenv().getOrDefault("DOSIMETRY_MYSQL_PASSWORD", "");
    }
}
