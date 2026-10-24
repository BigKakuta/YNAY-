package io.ynay.web;

import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled React SPA from {@code classpath:/static/}. Existing files are served as-is;
 * any other GET for a path without a file extension (a client-side route such as
 * {@code /budgets/123/accounts}) gets {@code index.html}. Server-owned prefixes ({@code /api},
 * {@code /mcp}, {@code /oauth2}, ...) never fall back to the SPA, so unknown API paths stay 404.
 *
 * <p>Boot's own {@code /**} static mapping is disabled ({@code spring.web.resources.add-mappings=false}).
 */
@Configuration(proxyBeanMethods = false)
public class SpaWebConfig implements WebMvcConfigurer {

    private static final List<String> SERVER_PREFIXES =
            List.of("api/", "api", "mcp/", "mcp", "oauth2/", "login/", "logout", "actuator/", "actuator",
                    ".well-known/", "error");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static boolean isClientRoute(String path) {
        for (String prefix : SERVER_PREFIXES) {
            if (prefix.endsWith("/") ? path.startsWith(prefix) : path.equals(prefix)) {
                return false;
            }
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        return !last.contains(".");
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = super.getResource(resourcePath, location);
            if (requested != null) {
                return requested;
            }
            return isClientRoute(resourcePath) ? super.getResource("index.html", location) : null;
        }
    }
}
