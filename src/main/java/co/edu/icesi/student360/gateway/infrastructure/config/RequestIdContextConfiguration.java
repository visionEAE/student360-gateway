package co.edu.icesi.student360.gateway.infrastructure.config;

import co.edu.icesi.student360.common.logging.MdcKeys;
import io.micrometer.context.ContextRegistry;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;

/**
 * Teaches Micrometer context propagation to carry the request id from the Reactor context into the
 * MDC, so JSON log lines written inside reactive chains still carry {@code requestId}.
 */
@Configuration
public class RequestIdContextConfiguration {

  public RequestIdContextConfiguration() {
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(
            MdcKeys.REQUEST_ID,
            () -> MDC.get(MdcKeys.REQUEST_ID),
            value -> MDC.put(MdcKeys.REQUEST_ID, value),
            () -> MDC.remove(MdcKeys.REQUEST_ID));
  }
}
