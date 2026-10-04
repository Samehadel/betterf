package com.betterf.identity.internal.service;

import com.betterf.identity.api.exception.IdentityException;
import com.google.common.net.InternetDomainName;

import java.net.URI;
import java.util.Locale;

public final class RootWebsite {
    private RootWebsite() {}

    public static String domain(String website) {
        try {
            var uri = new URI(website);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || host.endsWith(".")
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
                throw new IllegalArgumentException();
            host = host.toLowerCase(Locale.ROOT);
            if (!InternetDomainName.from(host).isTopDomainUnderRegistrySuffix())
                throw new IllegalArgumentException();
            return host;
        } catch (Exception exception) {
            throw new IdentityException(
                    400,
                    "INVALID_WEBSITE",
                    "Enter the HTTPS root website, for example https://company.co.uk, without"
                        + " subdomains, page paths, query strings, fragments, credentials, or"
                        + " ports.");
        }
    }
}
