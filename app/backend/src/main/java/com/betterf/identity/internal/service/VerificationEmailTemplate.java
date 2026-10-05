package com.betterf.identity.internal.service;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders packaged email templates with a shared engine and a per-message context. */
@Component
public class VerificationEmailTemplate {
    private final TemplateEngine engine;

    public VerificationEmailTemplate() {
        engine = new TemplateEngine();
        engine.addTemplateResolver(resolver(TemplateMode.HTML, "*.html", 1));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, "*.txt", 2));
    }

    public Content render(String verificationUrl) {
        var context = new Context(Locale.ENGLISH, Map.of("verificationUrl", verificationUrl));
        return new Content(
                engine.process("verification.txt", context),
                engine.process("verification.html", context));
    }

    private static ClassLoaderTemplateResolver resolver(
            TemplateMode mode, String pattern, int order) {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail/");
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setResolvablePatterns(Set.of(pattern));
        resolver.setOrder(order);
        resolver.setCacheable(true);
        return resolver;
    }

    public record Content(String plainText, String html) {}
}
