// @find: web mvc config, interceptors, permission interceptor registration, mvc setup
// @what: Registers shared web behaviour such as the permission interceptor.
// @flow: Wires PermissionInterceptor into every servlet service
package os.aiworkforce.platform.web.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import os.aiworkforce.platform.web.security.PermissionInterceptor;

/** Registers the shared interceptors in every servlet service. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebMvcConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**");
    }
}
