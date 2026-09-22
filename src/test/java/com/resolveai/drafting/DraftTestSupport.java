package com.resolveai.drafting;

import com.resolveai.platform.outbox.WorkerRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Drives {@code DraftWorker} deterministically, the same pattern every worker test uses. */
@Component
public class DraftTestSupport {

    @Autowired WorkerRuntime runtime;

    /** Runs DraftWorker until nothing is left to process. */
    public void runUntilSettled() {
        for (int i = 0; i < 50; i++) {
            int handled = runtime.workers().stream()
                    .filter(w -> w.name().equals("DraftWorker"))
                    .mapToInt(runtime::runOnce)
                    .sum();
            if (handled == 0) {
                return;
            }
        }
    }
}
