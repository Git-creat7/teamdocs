package asia.creat.model;

import asia.creat.common.exception.BusinessException;
import okhttp3.Dns;
import okhttp3.HttpUrl;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 用户模型仅访问公网 HTTPS；每次建立连接都检查 DNS，不能只在保存时检查。 */
public final class PublicModelEndpoint {
    private PublicModelEndpoint() { }

    public static String validate(String value) {
        HttpUrl url;
        try { url = HttpUrl.get(value.strip()); }
        catch (Exception error) { throw new BusinessException("模型地址必须是有效的公网 HTTPS Base URL"); }
        String host = url.host();
        if (!url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null || url.port() != 443
                || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || (!host.contains(".") && !host.contains(":"))) {
            throw new BusinessException("仅支持公网 HTTPS 默认端口，地址不能包含认证信息、查询参数或片段");
        }
        if (host.contains(":") || host.matches("[0-9.]+")) {
            try { if (!isPublic(InetAddress.getByName(host))) throw new UnknownHostException(); }
            catch (Exception error) { throw new BusinessException("模型地址不允许访问内网或保留地址"); }
        }
        return url.toString().replaceAll("/+$", "");
    }

    public static Dns dns(Dns delegate) {
        return hostname -> {
            List<InetAddress> addresses = delegate.lookup(hostname);
            if (addresses.isEmpty() || addresses.stream().anyMatch(address -> !isPublic(address))) {
                throw new UnknownHostException("Model endpoint resolved to a non-public address");
            }
            return addresses;
        };
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = Byte.toUnsignedInt(b[0]), second = Byte.toUnsignedInt(b[1]);
        if (b.length == 4) {
            int third = Byte.toUnsignedInt(b[2]);
            return first != 0 && first != 10 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254) && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192 && (second == 168 || second == 0 || (second == 88 && third == 99)))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    && !(first == 203 && second == 0 && third == 113);
        }
        // 仅允许原生全球单播，拒绝 NAT64、映射、6to4、Teredo、文档和其他特殊前缀。
        return b.length == 16 && (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && Byte.toUnsignedInt(b[2]) < 2)
                && !(first == 0x20 && second == 0x01 && Byte.toUnsignedInt(b[2]) == 0x0d && Byte.toUnsignedInt(b[3]) == 0xb8)
                && !(first == 0x3f && second == 0xff);
    }
}
