package com.linkedin.venice.service;

import com.linkedin.venice.utils.Utils;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Blueprint for all Services initiated from Venice Server
 *
 *
 */
public abstract class AbstractVeniceService {
  private final String serviceName;
  private final AtomicBoolean isStarted;

  public AbstractVeniceService(String serviceName) {
    this.serviceName = Utils.notNull(serviceName);
    isStarted = new AtomicBoolean(false);
  }

  public String getName() {
    return this.serviceName;
  }

  public void start()
      throws Exception {
    boolean isntStarted = isStarted.compareAndSet(false, true);
    if (!isntStarted) {
      throw new IllegalStateException("Service is already started!");
    }
    // TODO: Add logging
    startInner();
  }

  public void stop()
      throws Exception {
    synchronized (this) {
      if (!isStarted()) {
        //TODO: add logging
        return;
      }
      stopInner();
    }
  }

  public boolean isStarted() {
    return this.isStarted.get();
  }

  public abstract void startInner()
      throws Exception;

  public abstract void stopInner()
      throws Exception;
}
