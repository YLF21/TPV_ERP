package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TerminalConnectionAddressTest {

    @Test
    void returnsIpv4SocketPeer() {
        var request = requestFrom("192.0.2.15");

        assertThat(TerminalConnectionAddress.from(request)).isEqualTo(literal("192.0.2.15"));
    }

    @Test
    void returnsIpv6SocketPeer() {
        var request = requestFrom("2001:db8::15");

        assertThat(TerminalConnectionAddress.from(request)).isEqualTo(literal("2001:db8::15"));
    }

    @Test
    void acceptsSingleIpv4ForwardedAddressFromLoopbackPeer() {
        var request = requestFrom("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.23");

        assertThat(TerminalConnectionAddress.from(request)).isEqualTo(literal("198.51.100.23"));
    }

    @Test
    void acceptsSingleIpv6ForwardedAddressFromLoopbackPeer() {
        var request = requestFrom("::1");
        request.addHeader("X-Forwarded-For", "2001:db8::23");

        assertThat(TerminalConnectionAddress.from(request)).isEqualTo(literal("2001:db8::23"));
    }

    @Test
    void ignoresForwardedAddressFromNonLoopbackPeer() {
        var request = requestFrom("192.0.2.15");
        request.addHeader("X-Forwarded-For", "198.51.100.23");

        assertThat(TerminalConnectionAddress.from(request)).isEqualTo(literal("192.0.2.15"));
    }

    @Test
    void fallsBackToLoopbackPeerForDuplicateHeadersOrCommaLists() {
        var duplicate = requestFrom("127.0.0.1");
        duplicate.addHeader("X-Forwarded-For", "198.51.100.23");
        duplicate.addHeader("X-Forwarded-For", "198.51.100.24");

        var list = requestFrom("127.0.0.1");
        list.addHeader("X-Forwarded-For", "198.51.100.23, 198.51.100.24");

        assertThat(TerminalConnectionAddress.from(duplicate)).isEqualTo(literal("127.0.0.1"));
        assertThat(TerminalConnectionAddress.from(list)).isEqualTo(literal("127.0.0.1"));
    }

    @Test
    void fallsBackToPeerForHostnamesPortsAndIpv6Zones() {
        for (String forwarded : List.of("example.com", "192.0.2.10:8080", "[2001:db8::1]:8080", "fe80::1%eth0")) {
            var request = requestFrom("127.0.0.1");
            request.addHeader("X-Forwarded-For", forwarded);

            assertThat(TerminalConnectionAddress.from(request))
                    .as("forwarded value %s", forwarded)
                    .isEqualTo(literal("127.0.0.1"));
        }
    }

    @Test
    void returnsNullWhenPeerIsInvalidEvenIfForwardedAddressIsPresent() {
        var request = requestFrom("not-an-ip-address");
        request.addHeader("X-Forwarded-For", "198.51.100.23");

        assertThat(TerminalConnectionAddress.from(request)).isNull();
    }

    @Test
    void returnsNullForNullRequest() {
        assertThat(TerminalConnectionAddress.from(null)).isNull();
    }

    private static MockHttpServletRequest requestFrom(String remoteAddress) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static InetAddress literal(String value) {
        return InetAddress.ofLiteral(value);
    }
}
