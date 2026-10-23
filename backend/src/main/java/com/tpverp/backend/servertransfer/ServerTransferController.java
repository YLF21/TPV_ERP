package com.tpverp.backend.servertransfer;

import com.tpverp.backend.security.domain.*;
import com.tpverp.backend.terminal.TerminalRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/server-transfer")
@PreAuthorize("hasRole('ADMIN')")
public class ServerTransferController {
    private final ServerTransferService service; private final TerminalRepository terminals;
    private final UserAccountRepository users;private final PasswordEncoder passwords;
    public ServerTransferController(ServerTransferService service,TerminalRepository terminals,UserAccountRepository users,PasswordEncoder passwords) {
        this.service=service;this.terminals=terminals;this.users=users;this.passwords=passwords;
    }
    @GetMapping("/status") public ServerTransferService.Status status(Authentication auth,HttpServletRequest request) {
        authorize(auth,request,null,false);return service.status();
    }
    @PostMapping("/prepare") public ServerTransferService.Status prepare(@Valid @RequestBody Prepare body,Authentication auth,HttpServletRequest request) {
        authorize(auth,request,body.adminPassword(),true);return service.prepare(body.destinationPublicKey(),body.destinationProof());
    }
    @PostMapping("/{id}/seal") public ServerTransferService.Status seal(@PathVariable UUID id,@Valid @RequestBody Password body,Authentication auth,HttpServletRequest request) {
        authorize(auth,request,body.adminPassword(),true);return service.seal(id);
    }
    @PostMapping("/{id}/cancel") public ServerTransferService.Status cancel(@PathVariable UUID id,@Valid @RequestBody Password body,Authentication auth,HttpServletRequest request) {
        authorize(auth,request,body.adminPassword(),true);return service.cancel(id);
    }
    private void authorize(Authentication auth,HttpServletRequest request,String password,boolean reauthenticate) {
        try {
            if(!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) throw new IllegalArgumentException();
            if(request.getHeader("Forwarded")!=null || request.getHeader("X-Forwarded-For")!=null || request.getHeader("X-Real-IP")!=null)throw new IllegalArgumentException();
            if(!(auth.getPrincipal() instanceof UserAccount user) || !"ADMIN".equals(user.getNombre()) || !user.isProtegido() || user.getTienda()!=null || !user.isActivo())throw new IllegalArgumentException();
            if(!(auth.getDetails() instanceof OperationalSessionContext ctx) || ctx.terminalId()==null)throw new IllegalArgumentException();
            var terminal=terminals.findById(ctx.terminalId()).orElseThrow();
            if(!"001".equals(terminal.getWorkstationCode()))throw new IllegalArgumentException();
            var current=users.findById(user.getId()).orElseThrow();
            if(reauthenticate && !passwords.matches(password,current.getPasswordHash()))throw new IllegalArgumentException();
        } catch(Exception e) {throw new ResponseStatusException(HttpStatus.FORBIDDEN,"TRANSFER_LOCAL_ADMIN_001_REQUIRED");}
    }
    public record Prepare(@NotBlank String adminPassword,@NotBlank @Size(max=2048) String destinationPublicKey,@NotBlank @Size(max=2048) String destinationProof) {}
    public record Password(@NotBlank String adminPassword) {}
}
