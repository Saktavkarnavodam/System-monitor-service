package ru.diplom.monitoring.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Раздача исходников агентов мониторинга прямо с сервера.
 *
 * <p>Цель — превратить установку нового узла в один копипаст. Вместо
 * «найди в репозитории файл agent.py, скачай, положи в нужное место» —
 * пользователь делает:
 *
 * <pre>{@code
 * # Linux/macOS:
 * curl -sSL http://my-server:8080/install/agent.py | python3 - --token agt_xxx --node my-laptop
 *
 * # Windows:
 * irm http://my-server:8080/install/agent.ps1 -OutFile agent.ps1
 * .\agent.ps1 -Token agt_xxx -NodeName my-laptop
 * }</pre>
 *
 * <p>Эндпоинты <b>публичные</b> (доступны без аутентификации) — это просто
 * раздача статичного кода агента. Сама авторизация происходит, когда
 * запущенный агент идёт на /api/v1/* с токеном.
 *
 * <p>Источник файлов — папка {@code agent/} в корне проекта, которая
 * подключена в pom.xml как Maven-resource в {@code agent-installers/}.
 * Так агент остаётся одним источником правды и для разработчика, и для
 * раздачи через сервер.
 */
@RestController
@RequestMapping("/install")
@Tag(name = "Install", description = "Скачать готовый скрипт агента — один curl/iwr и узел готов")
public class AgentInstallController {

    @GetMapping(value = "/agent.py", produces = "text/x-python; charset=utf-8")
    @Operation(summary = "Python-агент (Linux/macOS/Windows, нужен Python 3 + psutil)")
    public ResponseEntity<String> python() throws IOException {
        return script("agent.py", "text/x-python; charset=utf-8");
    }

    @GetMapping(value = "/agent.ps1", produces = "text/plain; charset=utf-8")
    @Operation(summary = "PowerShell-агент (Windows, без зависимостей)")
    public ResponseEntity<String> powershell() throws IOException {
        return script("agent.ps1", "text/plain; charset=utf-8");
    }

    @GetMapping(value = "/agent.sh", produces = "text/x-shellscript; charset=utf-8")
    @Operation(summary = "Bash-агент (Linux/macOS, нужен curl + bc)")
    public ResponseEntity<String> bash() throws IOException {
        return script("agent.sh", "text/x-shellscript; charset=utf-8");
    }

    // -----------------------------------------------------------

    private ResponseEntity<String> script(String fileName, String contentType) throws IOException {
        ClassPathResource res = new ClassPathResource("agent-installers/" + fileName);
        if (!res.exists()) {
            return ResponseEntity.notFound().build();
        }
        String body;
        try (InputStream in = res.getInputStream()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        // Заголовок Content-Disposition: inline — чтобы curl и iwr не пытались
        // открывать как attachment, а отдавали в stdout/переменную.
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"");
        // Не кешируем — хотим, чтобы юзер всегда тянул свежую версию агента
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, 200);
    }
}
