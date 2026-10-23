package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class OfflineDisasterRecoveryCliConsoleResultTest {
    @TempDir Path directory;
    private static final String NAME="maintenance-result-"+"a".repeat(32)+".json";

    @Test void onlyPublicFieldsReachTheResultFile()throws Exception {
        var result=new ObjectMapper().readTree(OfflineDisasterRecoveryCli.publicResult(Map.of(
                "state","CANCELLED","transferId","id","recoveryId","recovery",
                "destinationPublicKey","public","destinationKeyFingerprint","fingerprint",
                "token","private-token","password","private-password","secrets",Map.of("key","private-key"))));
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.path("state").asText()).isEqualTo("CANCELLED");
        assertThat(result.toString()).doesNotContain("private-","password","secrets","token");
    }
    @Test void defaultOutputAndInvalidNamesDoNotCreateFiles()throws Exception {
        assertThat(OfflineDisasterRecoveryCli.openResultFile(directory,null)).isNull();
        for(String name:List.of("../"+NAME,"sub/"+NAME,NAME+":stream","journal.json","C:\\result.json"))
            assertThatThrownBy(()->OfflineDisasterRecoveryCli.openResultFile(directory,name)).isInstanceOf(IllegalArgumentException.class);
        try(var files=Files.list(directory)){assertThat(files.count()).isZero();}
    }
    @Test @EnabledOnOs(OS.WINDOWS) void reservesFreshFileAndNeverOverwritesExistingResult()throws Exception {
        restrictDirectory();
        try(var output=OfflineDisasterRecoveryCli.openResultFile(directory,NAME)){output.write(OfflineDisasterRecoveryCli.publicResult(Map.of("state","RECEIVED")));}
        String original=Files.readString(directory.resolve(NAME));
        assertThat(original).contains("RECEIVED");
        assertThatThrownBy(()->OfflineDisasterRecoveryCli.openResultFile(directory,NAME)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(directory.resolve(NAME))).isEqualTo(original);
    }
    @Test @EnabledOnOs(OS.WINDOWS) void refusesResultDirectoryReadableByUnrelatedUsers()throws Exception {
        restrictDirectory();
        var view=Files.getFileAttributeView(directory,AclFileAttributeView.class);
        var everyone=directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(
                com.sun.jna.platform.win32.Advapi32Util.getAccountBySid("S-1-1-0").name);
        var entries=new ArrayList<>(view.getAcl());
        entries.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(everyone)
                .setPermissions(AclEntryPermission.READ_DATA).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build());
        view.setAcl(entries);
        assertThatThrownBy(()->OfflineDisasterRecoveryCli.openResultFile(directory,NAME)).hasMessageContaining("unrelated reader");
        assertThat(Files.exists(directory.resolve(NAME))).isFalse();
    }
    private void restrictDirectory()throws Exception {
        var view=Files.getFileAttributeView(directory,AclFileAttributeView.class);
        view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(view.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT).build()));
    }
}
