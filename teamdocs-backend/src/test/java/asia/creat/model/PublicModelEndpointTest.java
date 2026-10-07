package asia.creat.model;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicModelEndpointTest {
    @Test
    void rejectsInternalAndAmbiguousUrls() {
        for (String url : List.of("http://api.example.com", "https://localhost", "https://127.0.0.1/v1",
                "https://2130706433", "https://169.254.169.254", "https://100.100.100.200",
                "https://192.168.0.1", "https://[::1]", "https://[::ffff:127.0.0.1]", "https://api.example.com:8443",
                "https://key@api.example.com", "https://api.example.com?api-key=x", "https://api.example.com/#x")) {
            assertThrows(RuntimeException.class, () -> PublicModelEndpoint.validate(url), url);
        }
        assertEquals("https://api.example.com/v1", PublicModelEndpoint.validate("https://api.example.com/v1/"));
    }

    @Test
    void rejectsDnsRebindingAndMixedPublicPrivateResults() throws Exception {
        var publicIp = InetAddress.getByName("8.8.8.8");
        var localIp = InetAddress.getByName("127.0.0.1");
        AtomicInteger lookups = new AtomicInteger();
        var dns = PublicModelEndpoint.dns(host -> List.of(lookups.getAndIncrement() == 0 ? publicIp : localIp));
        assertEquals(List.of(publicIp), dns.lookup("api.example.com"));
        assertThrows(UnknownHostException.class, () -> dns.lookup("api.example.com"));
        assertThrows(UnknownHostException.class, () -> PublicModelEndpoint.dns(host -> List.of(publicIp, localIp)).lookup("example.com"));
    }

    @Test
    void rejectsSpecialIpv6AndIpv4Networks() throws Exception {
        for (String ip : List.of("0.0.0.0", "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.2", "224.0.0.1",
                "fd00::1", "fe80::1", "64:ff9b::7f00:1", "2002:7f00:1::", "2001:db8::1", "2001::1")) {
            assertFalse(PublicModelEndpoint.isPublic(InetAddress.getByName(ip)), ip);
        }
        assertTrue(PublicModelEndpoint.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }
}
