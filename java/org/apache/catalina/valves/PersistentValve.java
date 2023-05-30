/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.StoreManager;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

/**
 * Valve that implements per-request session persistence. It is intended to be used with non-sticky load-balancers.
 * <p>
 * <b>USAGE CONSTRAINT</b>: To work correctly it requires a PersistentManager.
 * <p>
 * <b>USAGE CONSTRAINT</b>: To work correctly, this valve enforces the requirement that only one request per session is
 * processed at any one time.
 *
 * @author Jean-Frederic Clere
 */
public class PersistentValve extends ValveBase {

    // Saves a couple of calls to getClassLoader() on every request. Under high
    // load these calls took just long enough to appear as a hot spot (although
    // a very minor one) in a profiler.
    private static final ClassLoader MY_CLASSLOADER = PersistentValve.class.getClassLoader();

    private volatile boolean clBindRequired;

    protected Pattern filter = null;

    private ConcurrentMap<String,UsageCountingSemaphore> sessionToSemaphoreMap = new ConcurrentHashMap<>();

    private boolean semaphoreFairness = true;

    private boolean semaphoreBlockOnAcquire = true;

    private boolean semaphoreAcquireUninterruptibly = true;


    public PersistentValve() {
        super(true);
    }


    @Override
    public void setContainer(Container container) {
        super.setContainer(container);
        if (container instanceof Engine || container instanceof Host) {
            clBindRequired = true;
        } else {
            clBindRequired = false;
        }
    }


    /**
     * Select the appropriate child Context to process this request, based on the specified request URI. If no matching
     * Context can be found, return an appropriate HTTP error.
     *
     * @param request  Request to be processed
     * @param response Response to be produced
     *
     * @exception IOException      if an input/output error occurred
     * @exception ServletException if a servlet error occurred
     */
    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {

        // request without session
        if (isRequestWithoutSession(request.getDecodedRequestURI())) {
            getNext().invoke(request, response);
            return;
        }

        // Select the Context to be used for this Request
        Context context = request.getContext();
        if (context == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, sm.getString("standardHost.noContext"));
            return;
        }

        String sessionId = request.getRequestedSessionId();
        UsageCountingSemaphore semaphore = null;
        boolean mustReleaseSemaphore = true;

        try {
            // Acquire the per session semaphore
            if (sessionId != null) {
                semaphore = sessionToSemaphoreMap.compute(sessionId,
                        (k,v) -> v == null ? new UsageCountingSemaphore(semaphoreFairness) : v.incrementUsageCount());
                if (semaphoreBlockOnAcquire) {
                    if (semaphoreAcquireUninterruptibly) {
                        semaphore.acquireUninterruptibly();
                    } else {
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            mustReleaseSemaphore = false;
                            onSemaphoreNotAcquired(request, response);
                            return;
                        }
                    }
                } else {
                    if (!semaphore.tryAcquire()) {
                        onSemaphoreNotAcquired(request, response);
                        return;
                    }
                }
            }

            // Update the session last access time for our session (if any)
            Manager manager = context.getManager();
            if (sessionId != null && manager instanceof StoreManager) {
                Store store = ((StoreManager) manager).getStore();
                if (store != null) {
                    Session session = null;
                    try {
                        session = store.load(sessionId);
                    } catch (Exception e) {
                        container.getLogger().error("deserializeError");
                    }
                    if (session != null) {
                        if (!session.isValid() || isSessionStale(session, System.currentTimeMillis())) {
                            if (container.getLogger().isDebugEnabled()) {
                                container.getLogger().debug("session swapped in is invalid or expired");
                            }
                            session.expire();
                            store.remove(sessionId);
                        } else {
                            session.setManager(manager);
                            // session.setId(sessionId); Only if new ???
                            manager.add(session);
                            // ((StandardSession)session).activate();
                            session.access();
                            session.endAccess();
                        }
                    }
                }
            }
            if (container.getLogger().isDebugEnabled()) {
                container.getLogger().debug("sessionId: " + sessionId);
            }

            // Ask the next valve to process the request.
            getNext().invoke(request, response);

            // If still processing async, don't try to store the session
            if (!request.isAsync()) {
                // Read the sessionid after the response.
                // HttpSession hsess = hreq.getSession(false);
                Session hsess;
                try {
                    hsess = request.getSessionInternal(false);
                } catch (Exception ex) {
                    hsess = null;
                }
                String newsessionId = null;
                if (hsess != null) {
                    newsessionId = hsess.getIdInternal();
                }

                if (container.getLogger().isDebugEnabled()) {
                    container.getLogger().debug("newsessionId: " + newsessionId);
                }
                if (newsessionId != null) {
                    try {
                        bind(context);

                        /* store the session and remove it from the manager */
                        if (manager instanceof StoreManager) {
                            Session session = manager.findSession(newsessionId);
                            Store store = ((StoreManager) manager).getStore();
                            boolean stored = false;
                            if (session != null) {
                                synchronized (session) {
                                    if (store != null && session.isValid() &&
                                            !isSessionStale(session, System.currentTimeMillis())) {
                                        store.save(session);
                                        ((StoreManager) manager).removeSuper(session);
                                        session.recycle();
                                        stored = true;
                                    }
                                }
                            }
                            if (!stored) {
                                if (container.getLogger().isDebugEnabled()) {
                                    container.getLogger()
                                            .debug("newsessionId store: " + store + " session: " + session + " valid: " +
                                                    (session == null ? "N/A" : Boolean.toString(session.isValid())) +
                                                    " stale: " + isSessionStale(session, System.currentTimeMillis()));
                                }
                            }
                        } else {
                            if (container.getLogger().isDebugEnabled()) {
                                container.getLogger().debug("newsessionId Manager: " + manager);
                            }
                        }
                    } finally {
                        unbind(context);
                    }
                }
            }
        } finally {
            if (semaphore != null) {
                if (mustReleaseSemaphore) {
                    semaphore.release();
                }
                sessionToSemaphoreMap.computeIfPresent(
                        sessionId, (k,v) -> v.decrementAndGetUsageCount() == 0 ? null : v);
            }
        }
    }


    /**
     * Handle the case where a semaphore cannot be obtained. The default behaviour is to return a 429 (too many
     * requests) status code.
     *
     * @param request   The request that will not be processed
     * @param response  The response that will be used for this request
     *
     * @throws IOException If an I/O error occurs while working with the request or response
     */
    protected void onSemaphoreNotAcquired(Request request, Response response) throws IOException {
        response.sendError(429);
    }


    /**
     * Indicate whether the session has been idle for longer than its expiration date as of the supplied time. FIXME:
     * Probably belongs in the Session class.
     *
     * @param session The session to check
     * @param timeNow The current time to check for
     *
     * @return <code>true</code> if the session is past its expiration
     */
    protected boolean isSessionStale(Session session, long timeNow) {

        if (session != null) {
            int maxInactiveInterval = session.getMaxInactiveInterval();
            if (maxInactiveInterval >= 0) {
                int timeIdle = (int) (session.getIdleTimeInternal() / 1000L);
                if (timeIdle >= maxInactiveInterval) {
                    return true;
                }
            }
        }

        return false;
    }


    private void bind(Context context) {
        if (clBindRequired) {
            context.bind(MY_CLASSLOADER);
        }
    }


    private void unbind(Context context) {
        if (clBindRequired) {
            context.unbind(MY_CLASSLOADER);
        }
    }

    protected boolean isRequestWithoutSession(String uri) {
        Pattern f = filter;
        return f != null && f.matcher(uri).matches();
    }

    public String getFilter() {
        if (filter == null) {
            return null;
        }
        return filter.toString();
    }

    public void setFilter(String filter) {
        if (filter == null || filter.length() == 0) {
            this.filter = null;
        } else {
            try {
                this.filter = Pattern.compile(filter);
            } catch (PatternSyntaxException pse) {
                container.getLogger().error(sm.getString("persistentValve.filter.failure", filter), pse);
            }
        }
    }


    public boolean isSemaphoreFairness() {
        return semaphoreFairness;
    }


    public void setSemaphoreFairness(boolean semaphoreFairness) {
        this.semaphoreFairness = semaphoreFairness;
    }


    public boolean isSemaphoreBlockOnAcquire() {
        return semaphoreBlockOnAcquire;
    }


    public void setSemaphoreBlockOnAcquire(boolean semaphoreBlockOnAcquire) {
        this.semaphoreBlockOnAcquire = semaphoreBlockOnAcquire;
    }


    public boolean isSemaphoreAcquireUninterruptibly() {
        return semaphoreAcquireUninterruptibly;
    }


    public void setSemaphoreAcquireUninterruptibly(boolean semaphoreAcquireUninterruptibly) {
        this.semaphoreAcquireUninterruptibly = semaphoreAcquireUninterruptibly;
    }


    /*
     * The PersistentValve uses a per session semaphore to ensure that only one request accesses a session at a time.
     * To limit the size of the session ID to Semaphore map, the Semaphores are created when required and destroyed
     * (made eligible for GC) as soon as they are not required. Tracking usage in a thread-safe way requires a usage
     * counter that does not block. The Semaphore's internal tracking can't be used because the only way to increment
     * usage is via the acquire methods and they block. Therefore, this class was created which uses a separate
     * AtomicLong long to track usage.
     */
    private static class UsageCountingSemaphore {
        private final AtomicLong usageCount = new AtomicLong(1);
        private final Semaphore semaphore;

        private UsageCountingSemaphore(boolean fairness) {
            semaphore = new Semaphore(1, fairness);
        }

        private UsageCountingSemaphore incrementUsageCount() {
            usageCount.incrementAndGet();
            return this;
        }

        private long decrementAndGetUsageCount() {
            return usageCount.decrementAndGet();
        }

        private void acquire() throws InterruptedException {
            semaphore.acquire();
        }

        private void acquireUninterruptibly() {
            semaphore.acquireUninterruptibly();
        }

        private boolean tryAcquire() {
            return semaphore.tryAcquire();
        }

        private void release() {
            semaphore.release();
        }
    }
}
