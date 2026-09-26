package com.tpverp.backend.supervision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Captures rendering failures that intentionally return an unrendered receipt after a sale commits. */
@Component
public class PrintFailureReporter {
    private static final Logger LOG = LoggerFactory.getLogger(PrintFailureReporter.class);
    private final ApplicationFailureRecorder recorder;

    public PrintFailureReporter(ApplicationFailureRecorder recorder) { this.recorder = recorder; }

    public void record(Throwable failure) {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            var request = attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
            recorder.record(SecurityContextHolder.getContext().getAuthentication(),
                    ApplicationFailureRecorder.Module.PRINTING, failure, request);
        } catch (RuntimeException reportingFailure) {
            // A reporting outage must not remove the print fallback or change the committed sale result.
            LOG.warn("PRINT_FAILURE_CAPTURE_UNAVAILABLE");
        }
    }
}
