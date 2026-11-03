package com.rajarajeshwari.finance.auth;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class AuthInterceptor implements HandlerInterceptor {

    static final String SESSION_ATTR = "finance.sessionUser";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equals(req.getMethod())) {
            return true;
        }
        HttpSession s = req.getSession(false);
        Object u = s == null ? null : s.getAttribute(SESSION_ATTR);
        if (u == null) {
            res.setStatus(401);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":\"Please log in\"}");
            return false;
        }
        req.setAttribute(Ctx.ATTR, u);
        return true;
    }
}
