package com.tpverp.backend.supervision;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Observes errors without resolving them or changing the existing exception/response contract. */
@Configuration
public class ApplicationFailureWebConfiguration {
    private static final String FAILURE = ApplicationFailureWebConfiguration.class.getName() + ".failure";
    // Keep the recorder dependency in the full application configuration. MVC test slices
    // discover WebMvcConfigurer classes but deliberately exclude service beans.
    @Bean
    WebMvcConfigurer applicationFailureObserver(ApplicationFailureRecorder recorder) {
        return new WebMvcConfigurer() {
            @Override
            public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                resolvers.add(0, (request, response, handler, exception) -> {
                    request.setAttribute(FAILURE, exception);
                    return null;
                });
            }

            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
                        Throwable observedFailure = request.getAttribute(FAILURE) instanceof Throwable observed ? observed : null;
                        Throwable failure = exception != null ? exception
                                : response.getStatus() >= 500 || isPrintFailure(observedFailure) ? observedFailure : null;
                        if (failure == null || !(handler instanceof HandlerMethod method)) return;
                        String owner = method.getBeanType().getName();
                        if (!owner.startsWith("com.tpverp.backend.") || owner.startsWith("com.tpverp.backend.supervision.")) return;
                        recorder.record(SecurityContextHolder.getContext().getAuthentication(),
                                isPrintFailure(failure) ? ApplicationFailureRecorder.Module.PRINTING : module(method), failure, request);
                    }
                }).addPathPatterns("/api/**");
            }
        };
    }

    private static boolean isPrintFailure(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (int depth = 0; failure != null && depth < 16 && seen.add(failure); depth++, failure = failure.getCause()) {
            if (failure instanceof com.tpverp.backend.document.template.PrintRenderingException) return true;
        }
        return false;
    }

    static ApplicationFailureRecorder.Module module(HandlerMethod handler) {
        String name = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
        if (name.toLowerCase(java.util.Locale.ROOT).contains("print")) return ApplicationFailureRecorder.Module.PRINTING;
        if (handler.getBeanType().getPackageName().endsWith(".document")) return ApplicationFailureRecorder.Module.SALES;
        if (handler.getBeanType().getPackageName().endsWith(".sync")) return ApplicationFailureRecorder.Module.SYNC;
        return ApplicationFailureRecorder.Module.APPLICATION;
    }
}
