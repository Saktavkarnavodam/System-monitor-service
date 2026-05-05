package ru.diplom.monitoring.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.diplom.monitoring.model.AgentToken;
import ru.diplom.monitoring.model.User;
import ru.diplom.monitoring.service.AgentTokenService;
import ru.diplom.monitoring.service.UserService;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Аутентификационный фильтр, поддерживающий <b>два</b> вида Bearer-токенов:
 *
 * <ol>
 *   <li><b>JWT</b> — короткоживущий токен веб-сессии. Выдаётся в /auth/login,
 *       содержит userId в subject, проверяется сигнатурой HS256.</li>
 *   <li><b>Agent-токен</b> (префикс {@code agt_}) — долгоживущий ключ для
 *       агентов мониторинга. Хранится в БД как SHA-256-хеш, проверяется
 *       поиском по хешу. Авторизует от имени владельца токена.</li>
 * </ol>
 *
 * Различие — по префиксу токена. Это позволяет агентам ходить с одним и тем
 * же ключом сутками, не делая логин-цикл, а юзерам в браузере — оставаться
 * на стандартном JWT-флоу.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;
    private final UserService userService;
    private final AgentTokenService agentTokenService;

    public JwtAuthFilter(JwtService jwtService,
                         UserService userService,
                         AgentTokenService agentTokenService) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.agentTokenService = agentTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            // === Ветка 1: agent-токен ===========================================
            if (token.startsWith(AgentTokenService.TOKEN_PREFIX)) {
                authenticateWithAgentToken(token, request);
            }
            // === Ветка 2: обычный JWT ===========================================
            else {
                authenticateWithJwt(token, request);
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticateWithJwt(String token, HttpServletRequest request) {
        try {
            Claims claims = jwtService.parse(token);
            String userId = claims.getSubject();
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
                User user = userOpt.get();
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        } catch (JwtException ex) {
            log.debug("JWT отклонён: {}", ex.getMessage());
        }
    }

    private void authenticateWithAgentToken(String token, HttpServletRequest request) {
        Optional<AgentToken> tokenOpt = agentTokenService.findActiveByToken(token);
        if (tokenOpt.isEmpty()) {
            log.debug("agent-токен не найден или отозван");
            return;
        }
        AgentToken at = tokenOpt.get();
        Optional<User> userOpt = userService.findById(at.getOwnerId());
        if (userOpt.isEmpty()) {
            log.warn("agent-токен {} ссылается на несуществующего юзера {}", at.getId(), at.getOwnerId());
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null) return;

        User user = userOpt.get();
        var authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name()),
                new SimpleGrantedAuthority("ROLE_AGENT")        // дополнительная роль — пометка
        );
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);

        // best-effort: апдейт lastUsedAt; если упадёт — запрос всё равно идёт
        try {
            agentTokenService.touch(at.getId());
        } catch (Exception ex) {
            log.debug("Не удалось обновить lastUsedAt: {}", ex.getMessage());
        }
    }
}
