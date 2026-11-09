package ca.vm.parcelflow.support.security;

import ca.vm.parcelflow.security.ParcelFlowRole;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

final class WithParcelFlowUserSecurityContextFactory implements WithSecurityContextFactory<WithParcelFlowUser> {

    @Override
    public SecurityContext createSecurityContext(WithParcelFlowUser annotation) {
        String subject = annotation.subject().isEmpty()
                ? "sub-" + annotation.username()
                : annotation.subject();
        String retailerId = annotation.role() == ParcelFlowRole.RETAILER ? annotation.retailerId() : null;
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(TestIdentities.principal(
                subject, annotation.username(), annotation.role(), retailerId));
        return context;
    }
}
