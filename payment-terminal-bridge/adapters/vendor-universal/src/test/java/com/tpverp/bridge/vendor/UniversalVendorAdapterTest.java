package com.tpverp.bridge.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.bridge.spi.*;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UniversalVendorAdapterTest {
    @Test
    void publishesProviderEntryPointsButFailsClosedWithoutAnSdk() {
        assertThat(ServiceLoader.load(PaymentTerminalAdapter.class).stream().map(provider -> provider.get().adapterId()))
                .contains("redsys-universal", "paytef-universal", "paycomet-universal");
        var adapter=new PaytefUniversalAdapter();
        assertThat(adapter.manifest().modes()).isEmpty();
        assertThat(adapter.supports(profile(TerminalExecutionMode.LIVE))).isFalse();
        assertThat(adapter.health(profile(TerminalExecutionMode.LIVE)).code()).isEqualTo("DRIVER_NOT_INSTALLED");
    }

    @Test
    void neverRoutesLiveToAnUncertifiedSdkDriver() {
        var adapter=new TestAdapter(List.of(new Driver(TerminalExecutionMode.LIVE,false)));
        assertThat(adapter.manifest().certifiedLiveDriverInstalled()).isFalse();
        assertThat(adapter.manifest().modes()).doesNotContain(TerminalExecutionMode.LIVE);
        assertThat(adapter.supports(profile(TerminalExecutionMode.LIVE))).isFalse();
    }

    @Test
    void routesOnlyAUniqueCompatibleCertifiedDriver() {
        var adapter=new TestAdapter(List.of(new Driver(TerminalExecutionMode.LIVE,true)));
        assertThat(adapter.manifest().certifiedLiveDriverInstalled()).isTrue();
        assertThat(adapter.supports(profile(TerminalExecutionMode.LIVE))).isTrue();
        assertThat(adapter.capabilities(profile(TerminalExecutionMode.LIVE))).containsExactly(BridgeCapability.CHARGE);
    }

    private static TerminalProfile profile(TerminalExecutionMode mode){return new TerminalProfile("t","PAYTEF","paytef-universal",mode,
            "M1","TCP_IP","windows:paytef",Map.of("protocol","SDK"));}
    private static final class TestAdapter extends UniversalVendorAdapter{TestAdapter(List<VendorTerminalDriver> drivers){super("paytef-universal","PAYTEF","PAYTEF",drivers);}}
    private record Driver(TerminalExecutionMode mode,boolean certified) implements VendorTerminalDriver{
        @Override public String provider(){return "PAYTEF";}@Override public String protocol(){return "SDK";}
        @Override public Set<String> connectionTypes(){return Set.of("TCP_IP");}@Override public boolean certifiedForLivePayments(){return certified;}
        @Override public boolean supports(TerminalProfile profile){return true;}@Override public Set<BridgeCapability> capabilities(TerminalProfile profile){return Set.of(BridgeCapability.CHARGE);}
        @Override public AdapterHealth health(TerminalProfile profile){return new AdapterHealth(true,"OK","test");}
        @Override public OperationResult pair(PairingRequest request,TerminalProfile profile){return OperationResult.failure("ERROR","test");}
        @Override public OperationResult operate(OperationRequest request,TerminalProfile profile){return new OperationResult(true,"APPROVED","ref","auth","OK",null);}
    }
}
