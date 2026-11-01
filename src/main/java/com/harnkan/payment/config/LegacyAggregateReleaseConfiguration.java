package com.harnkan.payment.config;

import com.harnkan.payment.service.DealPayoutService;
import com.harnkan.payment.service.PaymentService;
import com.harnkan.payment.service.ReleaseOutcome;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Compatibility for the deployed checkout snapshot, whose ReleaseOutcome was a nested record. */
@Configuration
public class LegacyAggregateReleaseConfiguration {
    @Bean
    static BeanPostProcessor aggregateLegacyRelease(ObjectProvider<DealPayoutService> payouts) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof PaymentService)) return bean;
                try {
                    var method = bean.getClass().getMethod("releaseFunds", String.class, String.class);
                    if (method.getReturnType().equals(ReleaseOutcome.class)) return bean;
                    var factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        if (!invocation.getMethod().getName().equals("releaseFunds")) return invocation.proceed();
                        var result = payouts.getObject().releaseFunds((String) invocation.getArguments()[0],
                                (String) invocation.getArguments()[1]);
                        return method.getReturnType().getConstructor(int.class, java.math.BigDecimal.class, java.util.List.class)
                                .newInstance(result.releasedCount(), result.totalAmount(), result.payments());
                    });
                    return factory.getProxy();
                } catch (NoSuchMethodException failure) { throw new IllegalStateException(failure); }
            }
        };
    }
}
