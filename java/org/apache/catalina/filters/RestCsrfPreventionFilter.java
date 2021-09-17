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
package org.apache.catalina.filters;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.catalina.util.RequestUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * Provides basic CSRF protection for REST APIs. The filter assumes that the
 * clients have adapted the transfer of the nonce through the 'X-CSRF-Token'
 * header.
 *
 * <pre>
 * Positive scenario:
 *           Client                            Server
 *              |                                 |
 *              | GET Fetch Request              \| JSESSIONID
 *              |---------------------------------| X-CSRF-Token
 *              |                                /| pair generation
 *              |/Response to Fetch Request       |
 *              |---------------------------------|
 * JSESSIONID   |\                                |
 * X-CSRF-Token |                                 |
 * pair cached  | POST Request with valid nonce  \| JSESSIONID
 *              |---------------------------------| X-CSRF-Token
 *              |                                /| pair validation
 *              |/ Response to POST Request       |
 *              |---------------------------------|
 *              |\                                |
 *
 * Negative scenario:
 *           Client                            Server
 *              |                                 |
 *              | POST Request without nonce     \| JSESSIONID
 *              |---------------------------------| X-CSRF-Token
 *              |                                /| pair validation
 *              |/Request is rejected             |
 *              |---------------------------------|
 *              |\                                |
 *
 *           Client                            Server
 *              |                                 |
 *              | POST Request with invalid nonce\| JSESSIONID
 *              |---------------------------------| X-CSRF-Token
 *              |                                /| pair validation
 *              |/Request is rejected             |
 *              |---------------------------------|
 *              |\                                |
 * </pre>
 */
public class RestCsrfPreventionFilter extends CsrfPreventionFilterBase {
    private enum MethodType {
        NON_MODIFYING_METHOD, MODIFYING_METHOD
    }
    private final Log log = LogFactory.getLog(RestCsrfPreventionFilter.class);
    
    private static final Pattern NON_MODIFYING_METHODS_PATTERN = Pattern.compile("GET|HEAD|OPTIONS");

    private Set<String> pathsAcceptingParams = new HashSet<>();

    private String pathsDelimiter = ",";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Set the parameters
        super.init(filterConfig);

        // Put the expected request header name into the application scope
        filterConfig.getServletContext().setAttribute(
                Constants.CSRF_REST_NONCE_HEADER_NAME_KEY,
                Constants.CSRF_REST_NONCE_HEADER_NAME);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
            MethodType mType = MethodType.MODIFYING_METHOD;
            String method = ((HttpServletRequest) request).getMethod();
            if (method != null && NON_MODIFYING_METHODS_PATTERN.matcher(method).matches()) {
                mType = MethodType.NON_MODIFYING_METHOD;
            }

            RestCsrfPreventionStrategy strategy;
            switch (mType) {
            case NON_MODIFYING_METHOD:
                strategy = new FetchRequest();
                break;
            default:
                strategy = new StateChangingRequest();
                break;
            }

            if (!strategy.apply((HttpServletRequest) request, (HttpServletResponse) response)) {
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private abstract static class RestCsrfPreventionStrategy {

        abstract boolean apply(HttpServletRequest request, HttpServletResponse response)
                throws IOException;

        protected String extractNonceFromRequestHeader(HttpServletRequest request, String key) {
            return request.getHeader(key);
        }

        protected String[] extractNonceFromRequestParams(HttpServletRequest request, String key) {
            return request.getParameterValues(key);
        }

        protected void storeNonceToResponse(HttpServletResponse response, String key, String value) {
            response.setHeader(key, value);
        }

        protected String extractNonceFromSession(HttpSession session, String key) {
            return session == null ? null : (String) session.getAttribute(key);
        }

        protected void storeNonceToSession(HttpSession session, String key, Object value) {
            session.setAttribute(key, value);
        }
    }

    private class StateChangingRequest extends RestCsrfPreventionStrategy {

        @Override
        public boolean apply(HttpServletRequest request, HttpServletResponse response)
                throws IOException {
            String nonceFromRequest = extractNonceFromRequest(request);
            HttpSession session = request.getSession(false);
            String nonceFromSession = extractNonceFromSession(session,
                    Constants.CSRF_REST_NONCE_SESSION_ATTR_NAME);
            if (isValidStateChangingRequest(
                    nonceFromRequest,
                    nonceFromSession)) {
                return true;
            }

            storeNonceToResponse(response, Constants.CSRF_REST_NONCE_HEADER_NAME,
                    Constants.CSRF_REST_NONCE_HEADER_REQUIRED_VALUE);
            response.sendError(getDenyStatus(),
                    sm.getString("restCsrfPreventionFilter.invalidNonce"));
            if (log.isErrorEnabled()) {
                log.error("CSRF validation for REST failed! Request with method [" + request.getMethod() + "] and URI ["
                        + RequestUtil.filter(request.getRequestURI())
                        + "] will be rejected. Details: request has sessionid ["
                        + (request.getRequestedSessionId() != null)
                        + "]; requested session exists [" + (session != null )
                        + "]; crft nonce in request exists ["   + (nonceFromRequest != null)
                        + "]; csrf nonce in session exists [" + (nonceFromSession != null) + "].");
            }
            return false;
        }

        private boolean isValidStateChangingRequest(String reqNonce, String sessionNonce) {
            return reqNonce != null && sessionNonce != null
                    && Objects.equals(reqNonce, sessionNonce);
        }

        private String extractNonceFromRequest(HttpServletRequest request) {
            String nonceFromRequest = extractNonceFromRequestHeader(request,
                    Constants.CSRF_REST_NONCE_HEADER_NAME);
            if ((nonceFromRequest == null || Objects.equals("", nonceFromRequest))
                    && !getPathsAcceptingParams().isEmpty()
                    && getPathsAcceptingParams().contains(getRequestedPath(request))) {
                nonceFromRequest = extractNonceFromRequestParams(request);
            }
            return nonceFromRequest;
        }

        private String extractNonceFromRequestParams(HttpServletRequest request) {
            String[] params = extractNonceFromRequestParams(request,
                    Constants.CSRF_REST_NONCE_HEADER_NAME);
            if (params != null && params.length > 0) {
                String nonce = params[0];
                for (String param : params) {
                    if (!Objects.equals(param, nonce)) {
                        if (log.isErrorEnabled()) {
                            log.error("Different CSRF nonces are sent as request paramaters, none of them will be used." 
                                    + "Request is with method: [" + request.getMethod() + "] and URI [" + RequestUtil.filter(request.getRequestURI()) + "].");
                        }
                        return null;
                    }
                }
                return nonce;
            }
            return null;
        }
    }

    private class FetchRequest extends RestCsrfPreventionStrategy {

        @Override
        public boolean apply(HttpServletRequest request, HttpServletResponse response) {
            if (Constants.CSRF_REST_NONCE_HEADER_FETCH_VALUE
                    .equalsIgnoreCase(extractNonceFromRequestHeader(request,
                            Constants.CSRF_REST_NONCE_HEADER_NAME))) {
                String nonceFromSessionStr = extractNonceFromSession(request.getSession(false),
                        Constants.CSRF_REST_NONCE_SESSION_ATTR_NAME);
                if (nonceFromSessionStr == null) {
                    nonceFromSessionStr = generateNonce();
                    storeNonceToSession(Objects.requireNonNull(request.getSession(true)),
                            Constants.CSRF_REST_NONCE_SESSION_ATTR_NAME, nonceFromSessionStr);
                }
                storeNonceToResponse(response, Constants.CSRF_REST_NONCE_HEADER_NAME,
                        nonceFromSessionStr);
                if (log.isDebugEnabled()) {
                    log.debug("CSRF Fetch request is succesfully handled - nonce is added to the response."
                            + "Request is with method: [" + request.getMethod() + "] and URI [" + RequestUtil.filter(request.getRequestURI() + "]."));
                }
            }
            return true;
        }

    }

    /**
     * A comma separated list of URLs that can accept nonces via request
     * parameter 'X-CSRF-Token'. For use cases when a nonce information cannot
     * be provided via header, one can provide it via request parameters. If
     * there is a X-CSRF-Token header, it will be taken with preference over any
     * parameter with the same name in the request. Request parameters cannot be
     * used to fetch new nonce, only header.
     *
     * @param pathsList
     *            Comma separated list of URLs to be configured as paths
     *            accepting request parameters with nonce information.
     */
    public void setPathsAcceptingParams(String pathsList) {
        if (pathsList != null) {
            for (String element : pathsList.split(pathsDelimiter)) {
                    pathsAcceptingParams.add(element.trim());
            }
        }
    }

    public Set<String> getPathsAcceptingParams() {
        return pathsAcceptingParams;
    }
}
