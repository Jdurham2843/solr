/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin.api;

import static org.apache.solr.handler.admin.CoreAdminHandler.CoreAdminAsyncTracker.COMPLETED;
import static org.apache.solr.handler.admin.CoreAdminHandler.CoreAdminAsyncTracker.FAILED;
import static org.apache.solr.handler.admin.CoreAdminHandler.CoreAdminAsyncTracker.RUNNING;

import java.util.function.Supplier;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.handler.api.V2ApiUtils;
import org.apache.solr.jersey.SolrJerseyResponse;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.tracing.TraceUtils;
import org.slf4j.MDC;

/**
 * A common parent for admin Core Jersey-based APIs.
 *
 * <p>This base class is used when creating Core APIs to allow extra bookkeeping tasks such as async
 * requests handling.
 */
public abstract class CoreAdminAPIBase extends JerseyResource {

  protected final CoreContainer coreContainer;
  protected final CoreAdminHandler.CoreAdminAsyncTracker coreAdminAsyncTracker;
  protected final SolrQueryRequest req;
  protected final SolrQueryResponse rsp;

  public CoreAdminAPIBase(
      CoreContainer coreContainer,
      CoreAdminHandler.CoreAdminAsyncTracker coreAdminAsyncTracker,
      SolrQueryRequest req,
      SolrQueryResponse rsp) {
    this.coreContainer = coreContainer;
    this.coreAdminAsyncTracker = coreAdminAsyncTracker;
    this.req = req;
    this.rsp = rsp;
  }

  /**
   * Wraps the subclasses logic with extra bookkeeping logic.
   *
   * <p>This method currently exists to enable async handling behavior for V2 Core APIs.
   *
   * <p>Since the logic for a given API lives inside the Supplier functional interface, checked
   * exceptions can't be thrown directly to the calling method. To throw a checked exception out of
   * the Supplier, wrap the exception using {@link CoreAdminAPIBase.CoreAdminAPIBaseException} and
   * throw it instead. This handle method will retrieve the checked exception from {@link
   * CoreAdminAPIBaseException} and throw it to the original calling method.
   *
   * @param solrJerseyResponse the response that the calling methods expects to return.
   * @param coreName the name of the core that work is being done against.
   * @param taskId an id provided for registering async work (if null, the task is executed
   *     synchronously)
   * @param actionName a name for the action being done.
   * @param supplier the work that the calling method wants done.
   * @return the supplied T solrJerseyResponse
   */
  public <T extends SolrJerseyResponse> T handlePotentiallyAsynchronousTask(
      T solrJerseyResponse, String coreName, String taskId, String actionName, Supplier<T> supplier)
      throws Exception {
    try {
      if (coreContainer == null) {
        throw new SolrException(
            SolrException.ErrorCode.BAD_REQUEST, "Core container instance missing");
      }
      final CoreAdminHandler.CoreAdminAsyncTracker.TaskObject taskObject =
          new CoreAdminHandler.CoreAdminAsyncTracker.TaskObject(taskId);

      if (taskId != null) {
        // Put the tasks into the maps for tracking
        if (coreAdminAsyncTracker.getRequestStatusMap(RUNNING).containsKey(taskId)
            || coreAdminAsyncTracker.getRequestStatusMap(COMPLETED).containsKey(taskId)
            || coreAdminAsyncTracker.getRequestStatusMap(FAILED).containsKey(taskId)) {
          throw new SolrException(
              SolrException.ErrorCode.BAD_REQUEST,
              "Duplicate request with the same requestid found.");
        }

        coreAdminAsyncTracker.addTask(RUNNING, taskObject);
      }

      MDCLoggingContext.setCoreName(coreName);
      TraceUtils.setDbInstance(req, coreName);
      if (taskId == null) {
        return supplier.get();
      } else {
        try {
          MDC.put("CoreAdminAPIBase.taskId", taskId);
          MDC.put("CoreAdminAPIBase.action", actionName);
          coreAdminAsyncTracker
              .getParallelExecutor()
              .execute(
                  () -> {
                    boolean exceptionCaught = false;
                    try {
                      T response = supplier.get();

                      V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, response);

                      taskObject.setRspObject(rsp);
                      taskObject.setOperationRspObject(rsp);
                    } catch (Exception e) {
                      exceptionCaught = true;
                      taskObject.setRspObjectFromException(e);
                    } finally {
                      coreAdminAsyncTracker.removeTask("running", taskObject.taskId);
                      if (exceptionCaught) {
                        coreAdminAsyncTracker.addTask("failed", taskObject, true);
                      } else {
                        coreAdminAsyncTracker.addTask("completed", taskObject, true);
                      }
                    }
                  });
        } finally {
          MDC.remove("CoreAdminAPIBase.taskId");
          MDC.remove("CoreAdminAPIBase.action");
        }
      }
    } catch (CoreAdminAPIBaseException e) {
      throw e.trueException;
    } finally {
      rsp.setHttpCaching(false);
    }

    return solrJerseyResponse;
  }
  /**
   * Helper RuntimeException to allow passing checked exceptions to the caller of the handle method.
   */
  protected static class CoreAdminAPIBaseException extends RuntimeException {
    Exception trueException;

    public CoreAdminAPIBaseException(Exception trueException) {
      this.trueException = trueException;
    }
  }
}
