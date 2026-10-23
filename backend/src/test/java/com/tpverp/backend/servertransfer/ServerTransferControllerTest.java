package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.tpverp.backend.security.domain.*;
import com.tpverp.backend.terminal.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

class ServerTransferControllerTest {
    final ServerTransferService service=mock(ServerTransferService.class);
    final TerminalRepository terminals=mock(TerminalRepository.class);
    final UserAccountRepository users=mock(UserAccountRepository.class);
    final PasswordEncoder passwords=mock(PasswordEncoder.class);
    final Authentication auth=mock(Authentication.class);
    final UserAccount user=mock(UserAccount.class);
    final Terminal terminal=mock(Terminal.class);
    final MockHttpServletRequest request=new MockHttpServletRequest();
    final UUID terminalId=UUID.randomUUID(),userId=UUID.randomUUID();
    final ServerTransferController controller=new ServerTransferController(service,terminals,users,passwords);

    @BeforeEach void protectedLocalAdmin() {
        request.setRemoteAddr("127.0.0.1");
        when(auth.getPrincipal()).thenReturn(user);
        when(auth.getDetails()).thenReturn(new OperationalSessionContext(terminalId,UUID.randomUUID()));
        when(user.getId()).thenReturn(userId);when(user.getNombre()).thenReturn("ADMIN");
        when(user.isProtegido()).thenReturn(true);when(user.isActivo()).thenReturn(true);
        when(user.getPasswordHash()).thenReturn("stored-hash");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(terminals.findById(terminalId)).thenReturn(Optional.of(terminal));
        when(terminal.getWorkstationCode()).thenReturn("001");
        when(passwords.matches("correct-password","stored-hash")).thenReturn(true);
    }
    @Test void onlyDirectLoopbackCanReachTransferService() {
        request.setRemoteAddr("192.168.1.10");
        assertThatThrownBy(()->controller.status(auth,request)).hasMessageContaining("TRANSFER_LOCAL_ADMIN_001_REQUIRED");
        request.setRemoteAddr("127.0.0.1");request.addHeader("X-Forwarded-For","127.0.0.1");
        assertThatThrownBy(()->controller.status(auth,request)).hasMessageContaining("TRANSFER_LOCAL_ADMIN_001_REQUIRED");
        verifyNoInteractions(service);
    }
    @Test void administratorOnAnotherTerminalCannotTransfer() {
        when(terminal.getWorkstationCode()).thenReturn("002");
        assertThatThrownBy(()->controller.status(auth,request)).hasMessageContaining("TRANSFER_LOCAL_ADMIN_001_REQUIRED");
        verifyNoInteractions(service);
    }
    @Test void wrongPasswordCannotFreezeOrCancel() {
        UUID transfer=UUID.randomUUID();var body=new ServerTransferController.Password("wrong-password");
        assertThatThrownBy(()->controller.seal(transfer,body,auth,request)).hasMessageContaining("TRANSFER_LOCAL_ADMIN_001_REQUIRED");
        assertThatThrownBy(()->controller.cancel(transfer,body,auth,request)).hasMessageContaining("TRANSFER_LOCAL_ADMIN_001_REQUIRED");
        verifyNoInteractions(service);
    }
    @Test void reauthenticatedProtectedAdminCanSealOnLocal001() {
        UUID transfer=UUID.randomUUID();
        controller.seal(transfer,new ServerTransferController.Password("correct-password"),auth,request);
        verify(service).seal(transfer);
    }
}
