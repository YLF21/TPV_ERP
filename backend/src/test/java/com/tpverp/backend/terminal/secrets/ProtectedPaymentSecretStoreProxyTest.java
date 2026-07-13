package com.tpverp.backend.terminal.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class ProtectedPaymentSecretStoreProxyTest {

    @Test
    void createsTransactionalClassProxy() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            var store = context.getBean(PaymentSecretStore.class);

            assertThat(AopUtils.isAopProxy(store)).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean
        ProtectedPaymentSecretStore paymentSecretStore() {
            return new ProtectedPaymentSecretStore(
                    mock(PaymentSecretReferenceRepository.class),
                    mock(SecretProtector.class),
                    Clock.systemUTC(),
                    mock(PaymentSecretOwnerResolver.class));
        }
    }
}
