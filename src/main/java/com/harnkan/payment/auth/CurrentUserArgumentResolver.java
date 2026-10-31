package com.harnkan.payment.auth;

import com.harnkan.payment.exception.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentUser} from the X-User-Id header. The API gateway verifies the JWT, strips any
 * client-sent X-User-* headers and sets this one, so this service holds no JWT secret. The service port
 * must therefore not be reachable from outside the network.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    public static final String HEADER_USER_ID = "X-User-Id";
    static final int MAX_ID_LENGTH = 64;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String id = webRequest.getHeader(HEADER_USER_ID);
        if (id == null || id.isBlank()) {
            throw new UnauthorizedException("Missing " + HEADER_USER_ID);
        }
        id = id.trim();
        if (id.length() > MAX_ID_LENGTH) {
            throw new UnauthorizedException("Invalid user id: longer than " + MAX_ID_LENGTH + " characters");
        }
        return new CurrentUser(id);
    }
}
