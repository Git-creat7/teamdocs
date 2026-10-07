package asia.creat.teamdocsbackend.model;

import asia.creat.agent.AgentData.Run;
import asia.creat.config.AgentProperties;
import asia.creat.mapper.UserMapper;
import asia.creat.mapper.UserModelMapper;
import asia.creat.model.*;
import asia.creat.model.UserModelData.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserModelServiceTest {
    private final UserModelMapper mapper = mock(UserModelMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final AgentProperties system = new AgentProperties();
    private final ModelSecretCipher cipher = new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final UserModelService service = new UserModelService(cipher, system, users, new ObjectMapper());

    @BeforeEach
    void init() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Config.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        system.setEnabled(true); system.setAllowDocumentEgress(true); system.setModelName("system-model");
        when(users.lockActiveUser(1L)).thenReturn(1L);
    }

    private Config config() {
        Config row = new Config(); row.setUserId(1L); row.setEnabled(true); row.setVersion(2);
        row.setBaseUrl("https://api.example.com/v1"); row.setModelName("mine");
        row.setKeyCiphertext(cipher.encrypt(1, "config", "test-private-key"));
        when(mapper.selectById(1L)).thenReturn(row);
        return row;
    }

    @Test
    void viewNeverReturnsSecretAndDoesNotReadOtherUsers() throws Exception {
        config();
        var view = service.view(1);
        assertTrue(view.hasKey());
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("test-private-key"));
        assertFalse(service.view(2).hasKey());
        verify(mapper).selectById(2L);
    }

    @Test
    void reusesOwnKeyForNewAddressButNeverAnotherUsersKey() {
        config();
        assertEquals("test-private-key", service.draft(1, new Edit(true, 2L, "https://api.example.com/v1", "mine", "")).apiKey());
        assertEquals("test-private-key", service.draft(1, new Edit(true, 2L, "https://different.example.com/v1", "mine", "")).apiKey());
        assertThrows(RuntimeException.class, () -> service.draft(2, new Edit(true, 0L, "https://api.example.com/v1", "mine", "")));
    }

    @Test
    void runningSnapshotRemainsStableAfterConfigChangesAndDoesNotLeakInToString() {
        Config row = config();
        Run run = Run.builder().userId(1L).modelName("system-model").build();
        service.snapshot(run);
        assertEquals("mine", run.getModelName());
        assertFalse(run.toString().contains(run.getModelConfigCiphertext()));
        row.setModelName("new-model"); row.setKeyCiphertext(cipher.encrypt(1, "config", "changed-key"));
        assertEquals("mine", service.credentials(run).modelName());
        assertEquals("test-private-key", service.credentials(run).apiKey());
        run.setUserId(2L);
        assertThrows(RuntimeException.class, () -> service.credentials(run));
    }

    @Test
    void explicitSystemDenyWinsAndPersonalFailureNeverFallsBack() {
        Config row = config();
        system.setAllowDocumentEgress(false);
        assertThrows(RuntimeException.class, () -> service.snapshot(Run.builder().userId(1L).build()));
        system.setAllowDocumentEgress(true); row.setKeyCiphertext("broken");
        assertThrows(RuntimeException.class, () -> service.snapshot(Run.builder().userId(1L).build()));
        when(mapper.selectById(1L)).thenReturn(null);
        Run fallback = Run.builder().userId(1L).modelName("system-model").build();
        service.snapshot(fallback);
        assertNull(fallback.getModelConfigCiphertext());
    }

    @Test
    void personalClientsUseDefaultProxySelectorAndNoRedirects() throws Exception {
        var model = service.model(new Credentials("https://api.example.com/v1", "mine", "private-key"), true);
        OkHttpClient client = (OkHttpClient) ReflectionTestUtils.getField(model, "client");
        assertNull(client.proxy());
        assertSame(ProxySelector.getDefault(), client.proxySelector());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
        assertNotSame(Dns.SYSTEM, client.dns());

    }

    @Test
    void defaultJvmProxyIsUsedForChatAndProbesAndCanResolveLoopbackProxy() throws Exception {
        var original = ProxySelector.getDefault();
        var proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 7890));
        var selector = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) { return List.of(proxy); }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
        };
        try {
            ProxySelector.setDefault(selector);
            for (boolean extraction : new boolean[]{false, true}) {
                var model = service.model(new Credentials("https://api.example.com/v1", "mine", "private-key"), extraction);
                var client = (OkHttpClient) ReflectionTestUtils.getField(model, "client");
                assertNull(client.proxy());
                assertSame(selector, client.proxySelector());
                assertEquals(List.of(proxy), client.proxySelector().select(URI.create("https://api.example.com")));
                assertTrue(client.dns().lookup("localhost").stream().anyMatch(InetAddress::isLoopbackAddress));
            }
        } finally {
            ProxySelector.setDefault(original);
        }
    }

    @Test
    void personalRequestsAndProbesUseTheAdministratorProxy() {
        system.setProxyUrl("http://127.0.0.1:7890");
        for (boolean extraction : new boolean[]{true, false}) {
            var model = service.model(new Credentials("https://api.example.com/v1", "mine", "private-key"), extraction);
            var client = (OkHttpClient) ReflectionTestUtils.getField(model, "client");
            assertEquals(Proxy.Type.HTTP, client.proxy().type());
            assertEquals(7890, ((InetSocketAddress) client.proxy().address()).getPort());
            assertFalse(client.followRedirects());
            assertFalse(client.retryOnConnectionFailure());
        }
        system.setProxyUrl("http://user:password@127.0.0.1:7890");
        assertThrows(RuntimeException.class, () -> service.model(
                new Credentials("https://api.example.com/v1", "mine", "private-key"), false));
    }

    @Test
    void personalChatInheritsNoOutputLimitButAuxiliaryRequestsStayBounded() {
        assertEquals(0, system.getMaxOutputTokens());
        Credentials value = new Credentials("https://api.example.com/v1", "mine", "private-key");
        var chat = service.model(value, false);
        var auxiliary = service.model(value, true);
        AgentProperties chatProperties = (AgentProperties) ReflectionTestUtils.getField(chat, "properties");
        AgentProperties auxiliaryProperties = (AgentProperties) ReflectionTestUtils.getField(auxiliary, "properties");
        assertEquals(0, chatProperties.getMaxOutputTokens());
        assertEquals(512, auxiliaryProperties.getMaxOutputTokens());
    }

    @Test
    void rejectsStaleEditsAndStoresOnlyEncryptedKeys() {
        Config row = config();
        assertThrows(RuntimeException.class, () -> service.save(1, new Edit(true, 1L, row.getBaseUrl(), "mine", "next-key")));
        service.save(1, new Edit(true, 2L, row.getBaseUrl(), "mine", "next-key"));
        assertEquals(3, row.getVersion());
        assertNotEquals("next-key", row.getKeyCiphertext());
        assertEquals("next-key", cipher.decrypt(1, "config", row.getKeyCiphertext()));
        verify(mapper).insertOrUpdate(row);
    }
}
