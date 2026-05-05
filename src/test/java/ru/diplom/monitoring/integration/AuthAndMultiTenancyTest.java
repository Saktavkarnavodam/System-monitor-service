package ru.diplom.monitoring.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.diplom.monitoring.repository.AlertRepository;
import ru.diplom.monitoring.repository.AlertRuleRepository;
import ru.diplom.monitoring.repository.MetricRepository;
import ru.diplom.monitoring.repository.NodeRepository;
import ru.diplom.monitoring.repository.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end интеграционный тест: регистрация, вход, регистрация узлов
 * и проверка изоляции данных между пользователями.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AuthAndMultiTenancyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepo;
    @Autowired private NodeRepository nodeRepo;
    @Autowired private MetricRepository metricRepo;
    @Autowired private AlertRepository alertRepo;
    @Autowired private AlertRuleRepository ruleRepo;

    @BeforeEach
    void cleanup() {
        alertRepo.deleteAll();
        ruleRepo.deleteAll();
        metricRepo.deleteAll();
        nodeRepo.deleteAll();
        userRepo.deleteAll();
    }

    private String registerAndGetToken(String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(res.getResponse().getContentAsString());
        return node.get("token").asText();
    }

    private String registerNode(String token, String name) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/nodes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"host\":\"localhost\",\"port\":9000}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void register_login_me_workEndToEnd() throws Exception {
        String token = registerAndGetToken("alice", "secret123");

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        // повторная регистрация — должна провалиться
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"abcdef\"}"))
                .andExpect(status().is4xxClientError());

        // вход
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    @Test
    void unauthorized_requestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/nodes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void multiTenancy_userCannotSeeAnotherUsersNodes() throws Exception {
        String aliceToken = registerAndGetToken("alice", "alicepw1");
        String bobToken = registerAndGetToken("bob", "bobpw1234");

        String aliceNodeId = registerNode(aliceToken, "alice-node");
        String bobNodeId = registerNode(bobToken, "bob-node");

        // alice видит только свой узел
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(aliceNodeId));

        // bob видит только свой
        mvc.perform(get("/api/v1/nodes").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bobNodeId));

        // alice не может получить узел bob
        mvc.perform(get("/api/v1/nodes/" + bobNodeId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        // alice не может удалить узел bob
        mvc.perform(delete("/api/v1/nodes/" + bobNodeId).header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void multiTenancy_metricsAreIsolated() throws Exception {
        String aliceToken = registerAndGetToken("alice", "alicepw1");
        String bobToken = registerAndGetToken("bob", "bobpw1234");

        String aliceNode = registerNode(aliceToken, "alice-node");
        registerNode(bobToken, "bob-node");

        // alice отправляет метрику
        mvc.perform(post("/api/v1/metrics/nodes/" + aliceNode)
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":72.5}"))
                .andExpect(status().isOk());

        // alice видит её
        mvc.perform(get("/api/v1/metrics").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // bob — нет
        mvc.perform(get("/api/v1/metrics").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // bob не может отправить метрику от имени alice-node (узел не его)
        mvc.perform(post("/api/v1/metrics/nodes/" + aliceNode)
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":99}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentToken_authenticatesLikeJwt_butCanBeRevoked() throws Exception {
        // 1) логин юзера → получает JWT
        String jwt = registerAndGetToken("alice", "alicepw1");
        String nodeId = registerNode(jwt, "alice-laptop");

        // 2) выпускаем agent-токен
        MvcResult issuedRes = mvc.perform(post("/api/v1/agent-tokens")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"laptop-agent\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.info.name").value("laptop-agent"))
                .andReturn();
        JsonNode issued = json.readTree(issuedRes.getResponse().getContentAsString());
        String agentToken = issued.get("token").asText();
        String tokenId = issued.get("info").get("id").asText();
        org.junit.jupiter.api.Assertions.assertTrue(agentToken.startsWith("agt_"));

        // 3) с agent-токеном можно отправлять метрики (как от имени alice)
        mvc.perform(post("/api/v1/metrics/nodes/" + nodeId)
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":42.0}"))
                .andExpect(status().isOk());

        // 4) и можно читать /me — это alice
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        // 5) отзываем токен
        mvc.perform(delete("/api/v1/agent-tokens/" + tokenId)
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isNoContent());

        // 6) после отзыва agent-токен больше не работает
        mvc.perform(post("/api/v1/metrics/nodes/" + nodeId)
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"cpu.usage\",\"value\":50}"))
                .andExpect(status().isUnauthorized());

        // 7) JWT при этом продолжает работать
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test
    void installEndpoints_servePythonPowershellAndBashWithoutAuth() throws Exception {
        // /install/agent.py — публичный, отдаёт код Python-агента
        mvc.perform(get("/install/agent.py"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/x-python"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("def collect_metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("--token")));

        // /install/agent.ps1 — PowerShell
        mvc.perform(get("/install/agent.ps1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Get-Metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Token")));

        // /install/agent.sh — Bash
        mvc.perform(get("/install/agent.sh"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("collect_metrics")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("--token")));
    }

    @Test
    void agentToken_isolatedBetweenUsers() throws Exception {
        String alice = registerAndGetToken("alice", "alicepw1");
        String bob = registerAndGetToken("bob", "bobpw12345");

        // alice выпускает токен
        MvcResult res = mvc.perform(post("/api/v1/agent-tokens")
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alice-token\"}"))
                .andReturn();
        String tokenId = json.readTree(res.getResponse().getContentAsString())
                .get("info").get("id").asText();

        // bob не видит токен alice в своём списке
        mvc.perform(get("/api/v1/agent-tokens").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // bob не может отозвать токен alice
        mvc.perform(delete("/api/v1/agent-tokens/" + tokenId)
                        .header("Authorization", "Bearer " + bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void timeseriesEndpoint_works() throws Exception {
        String token = registerAndGetToken("alice", "alicepw1");
        String nodeId = registerNode(token, "alice-node");
        // запушим 3 метрики в одном бакете
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/metrics/nodes/" + nodeId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"cpu.usage\",\"value\":" + (10 + i) + "}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/metrics/timeseries")
                        .param("name", "cpu.usage")
                        .param("nodeId", nodeId)
                        .param("bucketSeconds", "3600")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].count").value(3));
    }
}
