package com.tpverp.bridge.vendor;

import com.tpverp.bridge.spi.*;
import java.util.*;

abstract class UniversalVendorAdapter implements PaymentTerminalAdapter {
    private final String adapterId;
    private final String provider;
    private final String displayName;
    private final List<VendorTerminalDriver> drivers;

    UniversalVendorAdapter(String adapterId, String provider, String displayName) {
        this(adapterId, provider, displayName, load(provider));
    }

    UniversalVendorAdapter(String adapterId, String provider, String displayName, List<VendorTerminalDriver> source) {
        this.adapterId=adapterId;this.provider=provider;this.displayName=displayName;
        var indexed=new LinkedHashMap<String,VendorTerminalDriver>();
        for(var driver:List.copyOf(source)){
            if(!provider.equals(normalize(driver.provider())))continue;
            var key=normalize(driver.protocol())+":"+driver.mode();
            if(indexed.putIfAbsent(key,driver)!=null)throw new IllegalArgumentException("Duplicate vendor driver: "+key);
        }
        drivers=List.copyOf(indexed.values());
    }

    @Override public String adapterId(){return adapterId;}
    @Override public String provider(){return provider;}
    @Override public AdapterManifest manifest(){
        var eligible=drivers.stream().filter(driver->driver.mode()!=TerminalExecutionMode.LIVE||driver.certifiedForLivePayments()).toList();
        return new AdapterManifest(adapterId,provider,displayName,
                eligible.stream().map(VendorTerminalDriver::mode).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                eligible.stream().map(VendorTerminalDriver::protocol).map(UniversalVendorAdapter::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                eligible.stream().flatMap(driver->driver.connectionTypes().stream()).map(UniversalVendorAdapter::normalize).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                eligible.stream().anyMatch(driver->driver.mode()==TerminalExecutionMode.LIVE&&driver.certifiedForLivePayments()));
    }
    @Override public void initialize(AdapterRuntime runtime){drivers.forEach(driver->driver.initialize(runtime));}
    @Override public boolean supports(TerminalProfile profile){return selected(profile).isPresent();}
    @Override public Set<BridgeCapability> capabilities(TerminalProfile profile){return selected(profile).map(driver->Set.copyOf(driver.capabilities(profile))).orElse(Set.of());}
    @Override public AdapterHealth health(TerminalProfile profile){return selected(profile).map(driver->driver.health(profile)).orElseGet(()->AdapterHealth.unavailable("DRIVER_NOT_INSTALLED"));}
    @Override public OperationResult pair(PairingRequest request,TerminalProfile profile){return selected(profile).map(driver->driver.pair(request,profile)).orElseGet(UniversalVendorAdapter::missing);}
    @Override public OperationResult operate(OperationRequest request,TerminalProfile profile){return selected(profile).map(driver->driver.operate(request,profile)).orElseGet(UniversalVendorAdapter::missing);}
    @Override public void close() throws Exception{Exception failure=null;for(var driver:drivers)try{driver.close();}catch(Exception ex){if(failure==null)failure=ex;else failure.addSuppressed(ex);}if(failure!=null)throw failure;}

    private Optional<VendorTerminalDriver> selected(TerminalProfile profile){
        if(profile==null||!provider.equals(profile.provider()))return Optional.empty();
        var requested=normalize(profile.parameters().getOrDefault("protocol","AUTO"));
        var matches=drivers.stream().filter(driver->driver.mode()==profile.mode())
                .filter(driver->profile.mode()!=TerminalExecutionMode.LIVE||driver.certifiedForLivePayments())
                .filter(driver->"AUTO".equals(requested)||normalize(driver.protocol()).equals(requested))
                .filter(driver->{try{return driver.supports(profile);}catch(RuntimeException ex){return false;}}).toList();
        return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
    }
    private static List<VendorTerminalDriver> load(String provider){var result=new ArrayList<VendorTerminalDriver>();ServiceLoader.load(VendorTerminalDriver.class,UniversalVendorAdapter.class.getClassLoader()).forEach(driver->{if(provider.equals(normalize(driver.provider())))result.add(driver);});return result;}
    private static String normalize(String value){if(value==null||!value.matches("[A-Za-z0-9._-]{1,64}"))throw new IllegalArgumentException("Vendor identifier");return value.toUpperCase(Locale.ROOT);}
    private static OperationResult missing(){return OperationResult.failure("SDK_NOT_INSTALLED","Controlador fisico certificado no instalado");}
}
