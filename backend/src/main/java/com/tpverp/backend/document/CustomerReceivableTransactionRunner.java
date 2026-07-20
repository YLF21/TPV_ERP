package com.tpverp.backend.document;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerReceivableTransactionRunner {

    @Transactional
    public <T> T run(Supplier<T> work) {
        return work.get();
    }
}
